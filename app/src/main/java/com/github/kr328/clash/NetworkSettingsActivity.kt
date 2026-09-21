package com.github.kr328.clash

import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.theme.ClashTheme
import com.github.kr328.clash.design.compose.theme.ClashThemeVariant
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendBroadcastSelf
import com.github.kr328.clash.util.startClashService
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NetworkSettingsActivity : BaseActivity() {
    private val srvStore by lazy { ServiceStore(this) }

    override suspend fun main() {

        if (clashRunning) {
            Toast.makeText(this, R.string.options_unavailable, Toast.LENGTH_LONG).show()
        }

        setContent {
            ClashTheme(variant = currentThemeVariant()) {
                NetworkSettingsScreen(
                    uiStore = uiStore,
                    srvStore = srvStore,
                    running = clashRunning,
                    onBack = { finish() },
                    onAccessControlPackages = {
                        startActivity(AccessControlActivity::class.intent)
                    },
                    onWifiAutomationChanged = ::setWifiAutomationEnabled,
                )
            }
        }

        while (isActive) {
            when (events.receive()) {
                Event.ClashStart, Event.ClashStop, Event.ServiceRecreated -> recreate()
                else -> Unit
            }
        }
    }

    private fun setWifiAutomationEnabled(enabled: Boolean) {
        srvStore.wifiAutomationEnabled = enabled

        // The running TunService re-evaluates immediately. If it is currently
        // paused on Wi-Fi, disabling automation reopens TUN without restarting
        // the whole service.
        sendBroadcastSelf(Intent(Intents.ACTION_WIFI_AUTOMATION_CHANGED))

        // If the user enables automation while Prizrak is completely stopped,
        // start the normal service from this visible activity. Android 12+
        // allows this user-initiated foreground-service start; once running,
        // the service will pause itself immediately if the current network is
        // validated Wi-Fi.
        if (enabled && !clashRunning) {
            val vpnRequest = startClashService()

            if (vpnRequest != null) {
                launch {
                    val result = startActivityForResult(
                        ActivityResultContracts.StartActivityForResult(),
                        vpnRequest,
                    )

                    if (result.resultCode == RESULT_OK) {
                        startClashService()
                    }
                }
            }
        }
    }

    private fun currentThemeVariant(): ClashThemeVariant {
        val cfg = resources.configuration
        return when (uiStore.darkMode) {
            DarkMode.Auto ->
                if (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                    ClashThemeVariant.Dark
                } else {
                    ClashThemeVariant.Light
                }
            DarkMode.ForceLight -> ClashThemeVariant.Light
            DarkMode.ForceDark -> ClashThemeVariant.Dark
            DarkMode.AlwaysSummer -> ClashThemeVariant.Summer
        }
    }
}
