package com.github.kr328.clash

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import java.util.concurrent.ConcurrentHashMap
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.R as ServiceR

/**
 * Keeps Wi-Fi automation alive independently from the Clash/VPN service.
 *
 * This must be a separate foreground service: when a trusted Wi-Fi is reached
 * the automation intentionally stops Clash, so a watcher living inside the
 * Clash runtime would die at exactly the moment it still needs to observe the
 * next Wi-Fi -> mobile transition.
 */
class WifiAutomationService : Service() {
    private data class NetworkState(
        @Volatile var capabilities: NetworkCapabilities? = null,
    )

    private val connectivity by lazy {
        checkNotNull(getSystemService<ConnectivityManager>())
    }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val networks = ConcurrentHashMap<Network, NetworkState>()

    @Volatile
    private var lastDesiredRunning: Boolean? = null

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks.putIfAbsent(network, NetworkState())
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            networks.getOrPut(network) { NetworkState() }.capabilities = networkCapabilities
            evaluate()
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            evaluate()
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
        startForegroundCompat(
            R.id.nf_wifi_automation,
            buildNotification(getString(DesignR.string.wifi_automation_waiting_network)),
        )

        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e("Wi-Fi automation: failed to register network callback", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UiStore(this).wifiAutomationEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A start command is also our cheap "settings changed, re-evaluate now"
        // signal. Clear the edge cache so changing the trusted SSID list takes
        // effect immediately even when the physical network did not change.
        lastDesiredRunning = null
        evaluate()

        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        networks.clear()
        stopForeground(true)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun evaluate() {
        val store = UiStore(this)
        if (!store.wifiAutomationEnabled) {
            stopSelf()
            return
        }

        val trusted = store.trustedWifiSsids
            .map(::normalizeSsid)
            .filter { it.isNotBlank() }
            .toSet()

        if (trusted.isEmpty()) {
            updateNotification(getString(DesignR.string.wifi_automation_no_networks))
            return
        }

        val validated = networks.values
            .mapNotNull { it.capabilities }
            .filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

        // Do not change VPN state during a handover gap. Once Wi-Fi or mobile
        // validates, onCapabilitiesChanged() will call us again with a stable
        // answer.
        if (validated.isEmpty()) {
            updateNotification(getString(DesignR.string.wifi_automation_waiting_network))
            return
        }

        val hasValidatedWifi = validated.any {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        val ssid = if (hasValidatedWifi) currentSsid() else null
        val onTrustedWifi = ssid != null && normalizeSsid(ssid) in trusted
        val desiredRunning = !onTrustedWifi

        if (lastDesiredRunning == desiredRunning) {
            updateStatusNotification(onTrustedWifi, ssid)
            return
        }

        lastDesiredRunning = desiredRunning

        if (desiredRunning) {
            val vpnPermission = startClashService()
            if (vpnPermission != null) {
                Log.w("Wi-Fi automation: VPN permission is not granted")
                updateNotification(getString(DesignR.string.wifi_automation_vpn_permission))
            } else if (hasValidatedWifi && ssid == null) {
                updateNotification(getString(DesignR.string.wifi_automation_ssid_unavailable))
            } else {
                updateNotification(getString(DesignR.string.wifi_automation_untrusted))
            }
        } else {
            stopClashService()
            updateStatusNotification(true, ssid)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        return try {
            val raw = wifiManager.connectionInfo?.ssid ?: return null
            val normalized = normalizeSsid(raw)

            normalized.takeUnless {
                it.isBlank() || it == WifiManager.UNKNOWN_SSID
            }
        } catch (e: SecurityException) {
            Log.w("Wi-Fi automation: SSID access denied", e)
            null
        } catch (e: Exception) {
            Log.w("Wi-Fi automation: unable to read SSID", e)
            null
        }
    }

    private fun updateStatusNotification(onTrustedWifi: Boolean, ssid: String?) {
        val text = when {
            onTrustedWifi && ssid != null ->
                getString(DesignR.string.wifi_automation_trusted, ssid)
            ssid == null && networks.values.any {
                it.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ->
                getString(DesignR.string.wifi_automation_ssid_unavailable)
            else ->
                getString(DesignR.string.wifi_automation_untrusted)
        }

        updateNotification(text)
    }

    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW,
            )
                .setName(getString(DesignR.string.wifi_automation_notification_channel))
                .build()
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ServiceR.drawable.ic_logo_service)
            .setColor(getColorCompat(ServiceR.color.color_clash))
            .setContentTitle(getString(DesignR.string.wifi_automation_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    R.id.nf_wifi_automation,
                    Intent(this, MainActivity::class.java)
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        ),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                )
            )
            .build()

    private fun updateNotification(text: String) {
        NotificationManagerCompat.from(this)
            .notify(R.id.nf_wifi_automation, buildNotification(text))
    }

    private fun normalizeSsid(value: String): String =
        value.trim().removeSurrounding("\"")

    companion object {
        private const val CHANNEL_ID = "wifi_automation_channel"
    }
}

fun Context.startWifiAutomationService() {
    startForegroundServiceCompat(Intent(this, WifiAutomationService::class.java))
}

fun Context.stopWifiAutomationService() {
    stopService(Intent(this, WifiAutomationService::class.java))
}
