package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.bridge.DohEngineStats
import com.example.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CodmDohVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statsJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDohTunnel()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_URL -> {
                val url = intent.getStringExtra(EXTRA_DOH_URL).orEmpty()
                if (url.isNotBlank()) {
                    NativeBridge.updateDohUrl(url)
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                val dohUrl = intent?.getStringExtra(EXTRA_DOH_URL)
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_DOH_URL
                startForegroundNotification()
                startDohTunnel(dohUrl)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startDohTunnel(dohUrl: String) {
        stopNativeAndInterface()

        val builder = Builder()
            .setSession("CODM Rust DoH Turbo")
            .setMtu(1500)
            .addAddress(TUN_CLIENT_IP, 32)
            .addDnsServer(TUN_DNS_IP)
            // Route ONLY the virtual DNS server IP so 100% of CODM UDP game traffic
            // bypasses the TUN and flies directly over native cellular/Wi-Fi.
            .addRoute(TUN_DNS_IP, 32)

        // CRITICAL: Exclude our own app so Rust reqwest/rustls DoH HTTPS connections
        // never loop back into the virtual TUN interface.
        runCatching {
            builder.addDisallowedApplication(packageName)
        }

        val pfd = builder.establish()
        if (pfd == null) {
            _isRunning.value = false
            stopSelf()
            return
        }

        val started = if (NativeBridge.isRustLoaded) {
            // Transfer ownership of the TUN file descriptor to Rust AsyncFd
            val detachedFd = pfd.detachFd()
            NativeBridge.startDohEngine(detachedFd, dohUrl)
        } else {
            vpnInterface = pfd
            true
        }

        if (!started) {
            runCatching { pfd.close() }
            _isRunning.value = false
            stopSelf()
            return
        }

        _isRunning.value = true
        _dohStats.value = _dohStats.value.copy(running = true, activeUrl = dohUrl)
        startStatsPolling(dohUrl)
    }

    private fun startStatsPolling(fallbackUrl: String) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                val stats = if (NativeBridge.isRustLoaded) {
                    NativeBridge.getDohStats()
                } else {
                    _dohStats.value.copy(running = true, activeUrl = fallbackUrl)
                }
                _dohStats.value = stats
                if (stats.lastRttMs > 0.0) {
                    NativeBridge.recordNetworkSample(stats.lastRttMs, 0L, 0L)
                }
                delay(1000L)
            }
        }
    }

    private fun stopNativeAndInterface() {
        statsJob?.cancel()
        statsJob = null
        NativeBridge.stopDohEngine()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun stopDohTunnel() {
        stopNativeAndInterface()
        _isRunning.value = false
        _dohStats.value = DohEngineStats(running = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_description)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CodmDohVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.doh_notification_title))
            .setContentText(getString(R.string.doh_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_disconnect), stopIntent)
            .build()
    }

    override fun onRevoke() {
        stopDohTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopNativeAndInterface()
        _isRunning.value = false
        _dohStats.value = DohEngineStats(running = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.vpn.ACTION_START_DOH"
        const val ACTION_STOP = "com.example.vpn.ACTION_STOP_DOH"
        const val ACTION_UPDATE_URL = "com.example.vpn.ACTION_UPDATE_DOH_URL"
        const val EXTRA_DOH_URL = "extra_doh_url"

        private const val TUN_CLIENT_IP = "10.10.0.1"
        private const val TUN_DNS_IP = "10.10.0.2"
        private const val DEFAULT_DOH_URL = "https://1.1.1.1/dns-query"
        private const val CHANNEL_ID = "codm_doh_vpn_channel"
        private const val NOTIFICATION_ID = 8484

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _dohStats = MutableStateFlow(DohEngineStats())
        val dohStats: StateFlow<DohEngineStats> = _dohStats.asStateFlow()

        fun start(context: Context, dohUrl: String) {
            val intent = Intent(context, CodmDohVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOH_URL, dohUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CodmDohVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateUrl(context: Context, dohUrl: String) {
            if (!_isRunning.value) return
            val intent = Intent(context, CodmDohVpnService::class.java).apply {
                action = ACTION_UPDATE_URL
                putExtra(EXTRA_DOH_URL, dohUrl)
            }
            context.startService(intent)
        }
    }
}
