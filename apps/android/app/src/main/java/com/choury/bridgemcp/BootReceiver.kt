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
        // enabled 在上线成功时置位、点「停止」时清除：从未成功连接或已停止的都不自动拉起
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
