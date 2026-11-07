package com.choury.bridgemcp.caps

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.choury.bridgemcp.BridgeApp
import com.choury.bridgemcp.BridgeService
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapResult
import com.choury.bridgemcp.R
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

// agent → 手机方向：post 发一条本地通知，cancel 撤销。
// （OpenClaw 无此方向；手机 → agent 的 list/actions 见 NotificationsListCapability。）
object NotificationCapability {

    private val nextId = AtomicInteger(1)

    private val POST_SCHEMA = """{
        "type": "object",
        "properties": {
            "id": {"type": "integer", "minimum": 0, "description": "notification id for later cancel; auto-incremented when omitted"},
            "title": {"type": "string", "minLength": 1, "description": "notification title"},
            "body": {"type": "string", "description": "notification body text"},
            "silent": {"type": "boolean", "default": false, "description": "post silently (no sound/vibration)"}
        },
        "required": ["title"]
    }"""

    private val CANCEL_SCHEMA = """{
        "type": "object",
        "properties": {
            "id": {"type": "integer", "minimum": 0, "description": "notification id to cancel"}
        },
        "required": ["id"]
    }"""

    fun register(add: (Capability, CapHandler) -> Unit) {
        add(
            Capability(
                name = "notification.post",
                description = "Post a local notification on this phone. Returns the id to use with notification.cancel.",
                inputSchema = POST_SCHEMA,
            ),
            { args, _ -> post(args) },
        )
        add(
            Capability(
                name = "notification.cancel",
                description = "Cancel a previously posted notification by id.",
                inputSchema = CANCEL_SCHEMA,
            ),
            { args, _ -> cancel(args) },
        )
    }

    private fun notifyManager() =
        BridgeApp.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun post(args: JSONObject): CapResult {
        val context = BridgeApp.instance
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("notification permission not granted; grant it in the app first")
        }
        val title = args.optString("title")
        if (title.isBlank()) throw IOException("title must not be empty")
        val silent = args.optBoolean("silent", false)
        val id = if (args.has("id")) args.getInt("id") else nextId.getAndIncrement()
        val channel = if (silent) BridgeService.CHANNEL_BRIDGE_SILENT else BridgeService.CHANNEL_BRIDGE

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(title)
            .setContentText(args.optString("body"))
            .setAutoCancel(true)
            .build()
        notifyManager().notify(id, notification)
        return CapResult.Json(JSONObject().put("id", id).put("channel", channel))
    }

    private fun cancel(args: JSONObject): CapResult {
        val id = args.getInt("id")
        notifyManager().cancel(id)
        return CapResult.Json(JSONObject().put("id", id).put("cancelled", true))
    }
}
