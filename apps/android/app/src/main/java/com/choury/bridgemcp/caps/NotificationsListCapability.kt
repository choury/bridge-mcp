package com.choury.bridgemcp.caps

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.choury.bridgemcp.BridgeApp
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapResult
import com.choury.bridgemcp.LogBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// NotificationListenerService：持有系统绑定实例，供 notifications.list/actions 快照与操作。
// bridge 协议无 client→server 推送通道，通知读取是拉取式（agent 主动调用）。
class BridgeNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: BridgeNotificationListener? = null
            private set

        fun component(context: Context) =
            ComponentName(context, BridgeNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) == true
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val title = sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        LogBus.log("notification received: ${sbn.packageName} ${title ?: ""}")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}

// 对齐 OpenClaw：notifications.list（快照）+ notifications.actions（open/dismiss/reply）。
object NotificationsListCapability {

    private val LIST_SCHEMA = """{"type": "object"}"""

    private val ACTIONS_SCHEMA = """{
        "type": "object",
        "properties": {
            "key": {"type": "string", "description": "notification key from notifications.list"},
            "action": {"type": "string", "enum": ["open", "dismiss", "reply"]},
            "replyText": {"type": "string", "description": "required when action is reply"}
        },
        "required": ["key", "action"]
    }"""

    fun register(add: (Capability, CapHandler) -> Unit) {
        add(
            Capability(
                name = "notifications.list",
                description = "Snapshot of this phone's current notifications (requires notification listener access). Returns enabled, connected, count, notifications[] with key/package/title/text/post_time/ongoing/actions.",
                inputSchema = LIST_SCHEMA,
            ),
            { _, _ -> list() },
        )
        add(
            Capability(
                name = "notifications.actions",
                description = "Act on one notification by key: open (launch its content intent), dismiss (remove it), or reply (fill its inline reply remote input with replyText).",
                inputSchema = ACTIONS_SCHEMA,
            ),
            { args, _ -> actions(args) },
        )
    }

    private fun list(): CapResult {
        val context = BridgeApp.instance
        val enabled = BridgeNotificationListener.isEnabled(context)
        val bound = BridgeNotificationListener.instance
        if (enabled && bound == null) {
            // 授权刚批下但系统尚未重绑时主动催一次
            NotificationListenerService.requestRebind(BridgeNotificationListener.component(context))
        }
        val items = JSONArray()
        for (sbn in bound?.activeNotifications.orEmpty()) items.put(sbnJson(sbn))
        return CapResult.Json(
            JSONObject()
                .put("enabled", enabled)
                .put("connected", bound != null)
                .put("count", items.length())
                .put("notifications", items)
        )
    }

    private fun sbnJson(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val actions = JSONArray()
        for (a in n.actions.orEmpty()) actions.put(a.title?.toString() ?: "")
        return JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("title", n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "")
            .put("text", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "")
            .put("post_time", n.`when`)
            .put("ongoing", (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
            .put("actions", actions)
    }

    private fun actions(args: JSONObject): CapResult {
        val context = BridgeApp.instance
        val listener = BridgeNotificationListener.instance
            ?: throw IOException("notification listener not connected; grant notification access in the app first")
        val key = args.optString("key")
        val action = args.optString("action").lowercase()
        val sbn = listener.activeNotifications.firstOrNull { it.key == key }
            ?: throw IOException("notification not found: $key")

        when (action) {
            "dismiss" -> listener.cancelNotification(key)
            "open" -> {
                val pi = sbn.notification.contentIntent
                    ?: throw IOException("notification has no content intent")
                pi.send()
            }
            "reply" -> {
                val replyText = args.optString("replyText")
                if (replyText.isBlank()) throw IOException("replyText required for reply action")
                val target = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                    ?: throw IOException("notification has no reply action")
                val results = Bundle()
                for (ri in target.remoteInputs!!) results.putCharSequence(ri.resultKey, replyText)
                val fill = Intent()
                RemoteInput.addResultsToIntent(target.remoteInputs, fill, results)
                target.actionIntent.send(context, 0, fill)
            }
            else -> throw IOException("invalid action \"$action\"; expected open|dismiss|reply")
        }
        return CapResult.Json(
            JSONObject().put("ok", true).put("key", key).put("action", action)
        )
    }
}
