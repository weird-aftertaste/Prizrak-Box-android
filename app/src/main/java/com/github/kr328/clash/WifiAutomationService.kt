package com.github.kr328.clash

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * The watcher must not live inside the Clash runtime: on Wi-Fi it intentionally
 * stops Clash, but still has to notice the later Wi-Fi -> mobile transition and
 * start it again.
 */
class WifiAutomationService : Service() {
    private data class NetworkState(
        @Volatile var capabilities: NetworkCapabilities? = null,
    )

    private val connectivity by lazy {
        checkNotNull(getSystemService<ConnectivityManager>())
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

        // A start command also means settings may have changed.
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
        if (!UiStore(this).wifiAutomationEnabled) {
            stopSelf()
            return
        }

        val validated = networks.values
            .mapNotNull { it.capabilities }
            .filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

        // Avoid toggling during the short handover gap between Wi-Fi and mobile.
        // As soon as one network validates, onCapabilitiesChanged() runs again.
        if (validated.isEmpty()) {
            updateNotification(getString(DesignR.string.wifi_automation_waiting_network))
            return
        }

        val onWifi = validated.any {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        val desiredRunning = !onWifi

        if (lastDesiredRunning == desiredRunning) {
            updateStatusNotification(onWifi)
            return
        }

        lastDesiredRunning = desiredRunning

        if (desiredRunning) {
            val vpnPermission = startClashService()
            if (vpnPermission != null) {
                Log.w("Wi-Fi automation: VPN permission is not granted")
                updateNotification(getString(DesignR.string.wifi_automation_vpn_permission))
            } else {
                updateNotification(getString(DesignR.string.wifi_automation_no_wifi))
            }
        } else {
            stopClashService()
            updateNotification(getString(DesignR.string.wifi_automation_wifi_connected))
        }
    }

    private fun updateStatusNotification(onWifi: Boolean) {
        updateNotification(
            getString(
                if (onWifi) {
                    DesignR.string.wifi_automation_wifi_connected
                } else {
                    DesignR.string.wifi_automation_no_wifi
                }
            )
        )
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
