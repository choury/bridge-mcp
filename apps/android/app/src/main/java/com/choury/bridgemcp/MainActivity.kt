package com.choury.bridgemcp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.choury.bridgemcp.caps.BridgeNotificationListener
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this, Intent(this, BridgeService::class.java)
                        .setAction(BridgeService.ACTION_PROJECTION)
                        .putExtra(BridgeService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                        .putExtra(BridgeService.EXTRA_PROJECTION_DATA, data)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    @Composable
    private fun Screen() {
        val prefs = remember { BridgeService.prefs(this) }
        val scope = rememberCoroutineScope()

        var server by remember { mutableStateOf(prefs.getString("server", "http://10.0.0.1:8765/ws") ?: "") }
        var clientId by remember { mutableStateOf(prefs.getString("client_id", deviceName()) ?: "") }
        var note by remember { mutableStateOf(prefs.getString("note", getString(R.string.note_default)) ?: "") }
        var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
        var adbPort by remember { mutableStateOf(prefs.getString("adb_port", "") ?: "") }
        var logs by remember { mutableStateOf<List<String>>(emptyList()) }
        DisposableEffect(Unit) {
            val job = scope.launch {
                LogBus.flow.collect { logs = (logs + it).takeLast(300) }
            }
            onDispose { job.cancel() }
        }

        // 系统设置里改权限后回来刷新状态
        var refresh by remember { mutableIntStateOf(0) }
        val status by BridgeStatus.state.collectAsState()
        val running = status.kind in setOf(
            BridgeStatus.Kind.CONNECTING, BridgeStatus.Kind.ONLINE, BridgeStatus.Kind.RECONNECTING
        )
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val locationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            LogBus.log(if (granted) "location permission granted" else "location permission denied")
            refresh++
        }
        val notifPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            LogBus.log(
                if (granted) "notification permission granted"
                else "notification permission denied; grant it in system settings"
            )
            refresh++
        }
        val bgLocationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            // Android 11+ 多数情况下不弹对话框、直接返回拒绝，需去应用详情页选「始终允许」
            if (granted) LogBus.log("background location granted")
            else LogBus.log("background location not granted; set location to 'Allow all the time' in the app settings")
            refresh++
        }
        val settingsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { refresh++ }

        fun save() {
            prefs.edit()
                .putString("server", server.trim())
                .putString("client_id", clientId.trim())
                .putString("note", note.trim())
                .putString("token", token)
                .putString("adb_port", adbPort.trim())
                .apply()
        }

        fun permGranted(p: String) =
            ContextCompat.checkSelfPermission(this@MainActivity, p) == PackageManager.PERMISSION_GRANTED

        // 运行时权限无公开的自撤销 API，撤销统一跳设置页由用户操作
        fun appDetailsPage() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))

        // 带 package 直达本 app 的开关页；部分镜像不支持该形式，回退到列表页
        fun allFilesPage() = run {
            val direct = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName"))
            if (direct.resolveActivity(packageManager) != null) direct
            else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }

        Scaffold { padding ->
            // refresh 只在 ON_RESUME/授权回调里自增；必须读一次它，从系统设置页返回时权限行才会重组
            val refreshTick = refresh
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(getString(R.string.title), style = MaterialTheme.typography.titleLarge)
                    // 徽章限宽换行并靠右：不加权重会先以整行测量，长文案吃掉标题宽度（标题零宽一字一行）
                    StatusChip(status, Modifier.weight(1f).wrapContentWidth(Alignment.End))
                }

                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text(getString(R.string.server_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    readOnly = running,
                )
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it },
                    label = { Text(getString(R.string.client_id_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    readOnly = running,
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(getString(R.string.note_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    readOnly = running,
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(getString(R.string.token_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    readOnly = running,
                )
                OutlinedTextField(
                    value = adbPort, onValueChange = { adbPort = it },
                    label = { Text(getString(R.string.adb_port_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    readOnly = running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            save()
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, BridgeService::class.java)
                                    .setAction(BridgeService.ACTION_START)
                            )
                        },
                        enabled = !running,
                    ) { Text(getString(R.string.action_connect)) }
                    RedOutlinedButton(getString(R.string.action_stop), enabled = running) {
                        save()
                        prefs.edit().putBoolean("enabled", false).apply()
                        startService(
                            Intent(this@MainActivity, BridgeService::class.java)
                                .setAction(BridgeService.ACTION_STOP)
                        )
                    }
                }

                HorizontalDivider()
                Text(getString(R.string.section_permissions), style = MaterialTheme.typography.titleMedium)

                PermissionRow(
                    title = getString(R.string.perm_location),
                    granted = permGranted(Manifest.permission.ACCESS_FINE_LOCATION),
                    actionText = getString(R.string.perm_authorize),
                    onGrant = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    onRevoke = { settingsLauncher.launch(appDetailsPage()) },
                )

                PermissionRow(
                    title = getString(R.string.perm_bg_location),
                    granted = permGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    actionText = getString(R.string.perm_authorize),
                    onGrant = { bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    onRevoke = { settingsLauncher.launch(appDetailsPage()) },
                )

                if (Build.VERSION.SDK_INT >= 33) {
                    PermissionRow(
                        title = getString(R.string.perm_notifications),
                        granted = permGranted(Manifest.permission.POST_NOTIFICATIONS),
                        actionText = getString(R.string.perm_authorize),
                        onGrant = { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onRevoke = { settingsLauncher.launch(appDetailsPage()) },
                    )
                }

                PermissionRow(
                    title = getString(R.string.perm_all_files),
                    granted = Build.VERSION.SDK_INT >= 30 && Environment_isExternalStorageManager(),
                    actionText = getString(R.string.perm_authorize),
                    onGrant = { settingsLauncher.launch(allFilesPage()) },
                    onRevoke = { settingsLauncher.launch(allFilesPage()) },
                )

                PermissionRow(
                    title = getString(R.string.perm_notif_listener),
                    granted = BridgeNotificationListener.isEnabled(this@MainActivity),
                    actionText = getString(R.string.perm_authorize),
                    onGrant = { settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onRevoke = { settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                )

                PermissionRow(
                    title = getString(R.string.perm_projection),
                    granted = false,
                    actionText = if (BridgeService.projection != null) getString(R.string.perm_stop_projection)
                    else getString(R.string.perm_authorize),
                    onGrant = {
                        if (BridgeService.projection != null) {
                            startService(
                                Intent(this@MainActivity, BridgeService::class.java)
                                    .setAction(BridgeService.ACTION_STOP_PROJECTION)
                            )
                        } else {
                            val mgr = getSystemService(MediaProjectionManager::class.java)
                            projectionLauncher.launch(mgr.createScreenCaptureIntent())
                        }
                    },
                )

                HorizontalDivider()
                Text(getString(R.string.section_logs), style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (line in logs.takeLast(120)) {
                        Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.DarkGray)
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusChip(status: BridgeStatus.State, modifier: Modifier = Modifier) {
        val bg = when (status.kind) {
            BridgeStatus.Kind.ONLINE -> Color(0xFFC8E6C9)
            BridgeStatus.Kind.CONNECTING, BridgeStatus.Kind.RECONNECTING -> Color(0xFFFFECB3)
            BridgeStatus.Kind.FAILED -> Color(0xFFFFCDD2)
            else -> Color(0xFFE0E0E0)
        }
        Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = bg) {
            Text(
                BridgeStatus.text(this@MainActivity, status),
                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 12.sp,
            )
        }
    }

    @Composable
    private fun RedOutlinedButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
            border = BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
        ) { Text(text) }
    }

    @Composable
    private fun PermissionRow(
        title: String,
        granted: Boolean,
        actionText: String,
        onGrant: () -> Unit,
        onRevoke: (() -> Unit)? = null,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.padding(top = 10.dp))
            when {
                granted && onRevoke != null ->
                    RedOutlinedButton(getString(R.string.perm_revoke)) { onRevoke() }
                granted -> Button(onClick = {}, enabled = false) { Text(getString(R.string.perm_granted)) }
                else -> Button(onClick = onGrant) { Text(actionText) }
            }
        }
    }
}

private fun Environment_isExternalStorageManager(): Boolean =
    android.os.Environment.isExternalStorageManager()
