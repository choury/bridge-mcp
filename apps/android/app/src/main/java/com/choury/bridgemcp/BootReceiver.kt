package com.choury.bridgemcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val p = BridgeService.prefs(context)
        // 用户点过「停止」的意图跨重启保留：仅 enabled 标记为 true 时自动拉起
        if (p.getBoolean("enabled", false) &&
            !p.getString("server", "").isNullOrBlank() &&
            !p.getString("client_id", "").isNullOrBlank()
        ) {
            ContextCompat.startForegroundService(
                context, Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_START)
            )
        }
    }
}
