package com.choury.bridgemcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.choury.bridgemcp.caps.DeviceCapability
import com.choury.bridgemcp.caps.FileCapability
import com.choury.bridgemcp.caps.LocationCapability
import com.choury.bridgemcp.caps.NotificationCapability
import com.choury.bridgemcp.caps.NotificationsListCapability
import com.choury.bridgemcp.caps.ScreenCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 连接状态广播：updateStatus 随每次状态迁移更新；文本经资源本地化（UI 徽章与常驻通知共用）。
object BridgeStatus {
    enum class Kind { NOT_CONNECTED, NOT_CONFIGURED, CONNECTING, ONLINE, RECONNECTING, FAILED }
    data class State(val kind: Kind, val detail: String = "")

    val state = MutableStateFlow(State(Kind.NOT_CONNECTED))

    fun text(context: Context, s: State): String = when (s.kind) {
        Kind.NOT_CONNECTED -> context.getString(R.string.status_not_connected)
        Kind.NOT_CONFIGURED -> context.getString(R.string.status_not_configured)
        Kind.CONNECTING -> context.getString(R.string.status_connecting, s.detail)
        Kind.ONLINE -> context.getString(R.string.status_online, s.detail)
        Kind.RECONNECTING -> context.getString(R.string.status_reconnecting, s.detail)
        Kind.FAILED -> context.getString(R.string.status_failed, s.detail)
    }
}

class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.choury.bridgemcp.START"
        const val ACTION_STOP = "com.choury.bridgemcp.STOP"
        const val ACTION_PROJECTION = "com.choury.bridgemcp.PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.choury.bridgemcp.STOP_PROJECTION"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"

        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_BRIDGE = "bridge"
        const val CHANNEL_BRIDGE_SILENT = "bridge_silent"

        const val PREFS = "bridge"
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 截屏会话期间由 BridgeService 持有投屏；ScreenCapability 读取
        @Volatile
        var projection: MediaProjection? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridgeJob: Job? = null
    private var connection: BridgeConnection? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION -> {
                startForeground(withProjection = true)
                acquireProjection(
                    intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED),
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA),
                )
            }
            ACTION_STOP_PROJECTION -> {
                // 用户手动结束：停掉 projection（系统“正在录制”提示随之消失），
                // FGS 退回纯 dataSync 类型
                projection?.stop()
                startForeground(withProjection = false)
                LogBus.log("screen capture stopped")
            }
            else -> startForeground(withProjection = false)
        }
        startBridgeLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeStatus.state.value = BridgeStatus.State(BridgeStatus.Kind.NOT_CONNECTED)
        bridgeJob?.cancel()
        connection?.shutdown()
        projection?.stop()
        projection = null
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannels() {
        for ((id, name, importance) in listOf(
            Triple(CHANNEL_SERVICE, getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_MIN),
            Triple(CHANNEL_BRIDGE, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CHANNEL_BRIDGE_SILENT, getString(R.string.notif_channel_bridge_silent), NotificationManager.IMPORTANCE_LOW),
        )) {
            notifications.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    // 点通知回 App 界面
    private fun contentIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    // Android 14 要求在调用 getMediaProjection 前先以 mediaProjection 类型进入前台；
    // dataSync 承载连接保活，两种类型同时声明。
    private fun startForeground(withProjection: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            val types = if (withProjection && Build.VERSION.SDK_INT >= 30) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(1, statusNotification(), types)
        } else {
            startForeground(1, statusNotification())
        }
    }

    private fun acquireProjection(resultCode: Int, resultData: Intent?) {
        if (resultData == null) return
        projection?.stop()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, resultData) ?: return
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 异步 onStop 可能来自已被替换的旧会话，只清当前会话
                if (projection === mp) {
                    projection = null
                    LogBus.log("screen capture authorization revoked")
                }
            }
        }, mainHandler)
        projection = mp
        LogBus.log("screen capture authorization active")
    }

    private fun readConfig(): Array<String> {
        val p = prefs(this)
        return arrayOf(
            p.getString("server", "") ?: "",
            p.getString("client_id", "") ?: "",
            p.getString("note", "") ?: "",
            p.getString("token", "") ?: "",
        )
    }

    private fun buildRegistry(): Pair<List<Capability>, Map<String, CapHandler>> {
        val caps = mutableListOf<Capability>()
        val handlers = mutableMapOf<String, CapHandler>()
        fun add(cap: Capability, handler: CapHandler) {
            caps.add(cap)
            handlers[cap.name] = handler
        }
        LocationCapability.register(::add)
        FileCapability.register(::add)
        NotificationCapability.register(::add)
        NotificationsListCapability.register(::add)
        DeviceCapability.register(::add)
        ScreenCapability.register(::add)
        return caps to handlers
    }

    private fun startBridgeLoop() {
        if (bridgeJob?.isActive == true) return
        bridgeJob = scope.launch {
            var backoff = 1_000L
            var welcomed = false
            while (isActive) {
                val (server, clientId, note, token) = readConfig()
                if (server.isBlank() || clientId.isBlank()) {
                    LogBus.log("missing server or client id; configure in the app and reconnect")
                    updateStatus(BridgeStatus.State(BridgeStatus.Kind.NOT_CONFIGURED))
                    return@launch
                }
                val (caps, handlers) = buildRegistry()
                val conn = BridgeConnection(
                    serverUrl = server,
                    helloMessage = Protocol.hello(clientId, note, token, caps),
                    handlers = handlers,
                    onWelcomed = {
                        welcomed = true
                        backoff = 1_000
                        LogBus.log("registered as \"$clientId\" (${caps.size} capabilities)")
                        updateStatus(BridgeStatus.State(BridgeStatus.Kind.ONLINE, clientId))
                    },
                    onDown = { LogBus.log("connection closed") },
                )
                connection = conn
                val failure = try {
                    updateStatus(BridgeStatus.State(BridgeStatus.Kind.CONNECTING, server))
                    conn.serve()
                    null
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
                connection = null
                if (!isActive) break
                if (failure != null) {
                    LogBus.log(if (welcomed) "connection lost: $failure" else "connect failed: $failure")
                    if (!welcomed) {
                        updateStatus(BridgeStatus.State(BridgeStatus.Kind.FAILED, failure))
                        return@launch
                    }
                }
                updateStatus(BridgeStatus.State(BridgeStatus.Kind.RECONNECTING, "${backoff / 1000}"))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private fun updateStatus(s: BridgeStatus.State) {
        BridgeStatus.state.value = s
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(BridgeStatus.text(this, s))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notifications.notify(1, notification)
    }
}
