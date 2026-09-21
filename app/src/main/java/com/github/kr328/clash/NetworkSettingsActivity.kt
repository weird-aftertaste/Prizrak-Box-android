package com.github.kr328.clash

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.theme.ClashTheme
import com.github.kr328.clash.design.compose.theme.ClashThemeVariant
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive

class NetworkSettingsActivity : BaseActivity() {
    private var pendingWifiAutomationEnable = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!pendingWifiAutomationEnable) return@registerForActivityResult

        pendingWifiAutomationEnable = false

        if (granted) {
            uiStore.wifiAutomationEnabled = true
            startWifiAutomationService()
        } else {
            uiStore.wifiAutomationEnabled = false
            Toast.makeText(
                this,
                R.string.wifi_automation_location_permission_denied,
                Toast.LENGTH_LONG,
            ).show()
        }

        recreate()
    }

    override suspend fun main() {
        val srvStore = ServiceStore(this)

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
                    onTrustedWifiChanged = {
                        if (uiStore.wifiAutomationEnabled) {
                            startWifiAutomationService()
                        }
                    },
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
        if (!enabled) {
            uiStore.wifiAutomationEnabled = false
            stopWifiAutomationService()
            return
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            uiStore.wifiAutomationEnabled = true
            startWifiAutomationService()
        } else {
            // Android classifies the connected SSID as location-sensitive
            // information. Ask only when the user actually enables automation.
            pendingWifiAutomationEnable = true
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
