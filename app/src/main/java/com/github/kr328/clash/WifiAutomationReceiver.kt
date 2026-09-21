package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.design.store.UiStore

/**
 * Restores Wi-Fi automation after reboot or an app update.
 */
class WifiAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (UiStore(context).wifiAutomationEnabled) {
                    context.startWifiAutomationService()
                }
            }
        }
    }
}
