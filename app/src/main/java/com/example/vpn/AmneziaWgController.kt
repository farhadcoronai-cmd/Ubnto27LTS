package com.example.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.bridge.NativeBridge
import com.example.bridge.TunnelState
import com.example.bridge.TunnelStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader

class AmneziaWgController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backend: GoBackend by lazy {
        GoBackend(context.applicationContext, NoopTunnelActionHandler())
    }

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var statsJob: Job? = null
    private var activeTunnel: CodmTunnel? = null
    private var connectedAtMillis: Long = 0L

    private inner class CodmTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            val mapped = when (newState) {
                Tunnel.State.UP -> TunnelState.CONNECTED
                Tunnel.State.DOWN -> TunnelState.DISCONNECTED
            }
            _stats.value = _stats.value.copy(state = mapped)
        }

        override fun isIpv4ResolutionPreferred(): Boolean = true
    }

    suspend fun startTunnel(
        tunnelName: String,
        rawConfigText: String,
        enforceCodmSplitTunnel: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val result = runCatching {
            _stats.value = _stats.value.copy(
                state = TunnelState.CONNECTING,
                errorMessage = null
            )

            val installedCodmPkg = detectInstalledCodmPackage()
            val canApplyCodmSplit = enforceCodmSplitTunnel && installedCodmPkg != null

            val preparedConfig = if (canApplyCodmSplit) {
                NativeBridge.optimizeAwgConfigForCodm(rawConfigText, true).optimizedConfig
            } else {
                rawConfigText
            }

            val runtimeConfigText = adaptConfigForGoBackend(
                configText = preparedConfig,
                installedCodmPkg = if (canApplyCodmSplit) installedCodmPkg else null,
                stripUnavailableSplit = enforceCodmSplitTunnel && installedCodmPkg == null
            )

            val parsedConfig = Config.parse(BufferedReader(StringReader(runtimeConfigText)))
            val endpointLabel = parsedConfig.peers.firstOrNull()?.endpoint?.toString().orEmpty()

            val sanitizedName = tunnelName
                .replace(Regex("[^a-zA-Z0-9_=+.-]"), "_")
                .take(Tunnel.NAME_MAX_LENGTH)
                .ifBlank { "codm_stealth" }

            val tunnel = CodmTunnel(sanitizedName)
            activeTunnel = tunnel

            backend.setState(tunnel, Tunnel.State.UP, parsedConfig)
            connectedAtMillis = System.currentTimeMillis()
            _stats.value = TunnelStats(
                state = TunnelState.CONNECTED,
                endpoint = endpointLabel
            )
            startTelemetryLoop(tunnel, endpointLabel)
            Unit
        }

        result.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { err ->
                val readableError = formatAwgException(err)
                _stats.value = TunnelStats(
                    state = TunnelState.ERROR,
                    errorMessage = readableError
                )
                Result.failure(IllegalStateException(readableError, err))
            }
        )
    }

    suspend fun stopTunnel() = withContext(Dispatchers.IO) {
        statsJob?.cancel()
        statsJob = null
        val tunnel = activeTunnel
        if (tunnel != null) {
            runCatching {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            }
        }
        activeTunnel = null
        connectedAtMillis = 0L
        _stats.value = TunnelStats(state = TunnelState.DISCONNECTED)
    }

    private fun detectInstalledCodmPackage(): String? {
        val pm = context.packageManager
        return listOf("com.activision.callofduty.shooter", "com.garena.game.codm").firstOrNull { pkg ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.isSuccess
        }
    }

    /**
     * Adapts AWG 2.0 configs for amneziawg-android 2.0.5 GoBackend parser:
     * - Strips S3/S4 (not yet supported by amneziawg-android 2.0.5 Interface.parse)
     * - Converts H1..H4 range syntax (e.g. 100000-200000) to its lower bound integer
     * - Adjusts IncludedApplications to the actual installed CODM package (or strips if CODM is not installed)
     */
    private fun adaptConfigForGoBackend(
        configText: String,
        installedCodmPkg: String?,
        stripUnavailableSplit: Boolean
    ): String {
        val out = mutableListOf<String>()
        var inInterface = false

        for (line in configText.lines()) {
            val trimmed = line.trim()
            if (trimmed.equals("[Interface]", ignoreCase = true)) {
                inInterface = true
                out += line
                continue
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inInterface = false
                out += line
                continue
            }

            if (inInterface && !trimmed.startsWith("#") && !trimmed.startsWith(";")) {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    val keyLower = key.lowercase()
                    val value = trimmed.substring(eqIdx + 1).trim()

                    // S3 / S4 are AWG 2.0 desktop/kernel extensions not parsed by amneziawg-android 2.0.5
                    if (keyLower == "s3" || keyLower == "s4") {
                        continue
                    }

                    // Convert H1..H4 ranges like "100000-200000" to single integer "100000"
                    if (keyLower in setOf("h1", "h2", "h3", "h4") && value.contains('-')) {
                        val lowerBound = value.substringBefore('-').trim()
                        if (lowerBound.toLongOrNull() != null) {
                            out += "$key = $lowerBound"
                            continue
                        }
                    }

                    if (keyLower == "includedapplications") {
                        if (stripUnavailableSplit) {
                            continue
                        }
                        if (installedCodmPkg != null && value.contains("com.activision.callofduty.shooter")) {
                            out += "IncludedApplications = $installedCodmPkg"
                            continue
                        }
                    }
                }
            }
            out += line
        }

        return out.joinToString("\n").trimEnd() + "\n"
    }

    private fun formatAwgException(err: Throwable): String {
        return when (err) {
            is BackendException -> {
                val extra = err.format?.joinToString(", ") { it?.toString().orEmpty() }
                    ?.takeIf { it.isNotBlank() }
                val causeMsg = err.cause?.message?.takeIf { it.isNotBlank() }
                when (err.reason) {
                    BackendException.Reason.VPN_NOT_AUTHORIZED ->
                        "مجوز VPN تایید نشده است (VPN_NOT_AUTHORIZED)."
                    BackendException.Reason.UNABLE_TO_START_VPN ->
                        "سرویس VPN شروع نشد (UNABLE_TO_START_VPN${causeMsg?.let { ": $it" }.orEmpty()})."
                    BackendException.Reason.TUN_CREATION_ERROR ->
                        "ساخت رابط TUN ناموفق بود (TUN_CREATION_ERROR)."
                    BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                        "آدرس Endpoint سرور پیدا نشد / خطای DNS (${extra ?: "بررسی اینترنت و Endpoint"})."
                    BackendException.Reason.GO_ACTIVATION_ERROR_CODE ->
                        "خطای هسته AmneziaWG Go (کد ${extra ?: "نامشخص"})."
                    BackendException.Reason.AWG_QUICK_CONFIG_ERROR_CODE ->
                        "خطا در پارامترهای awg-quick (${extra ?: "بررسی کانفیگ"})."
                    BackendException.Reason.TUNNEL_MISSING_CONFIG ->
                        "تنظیمات تونل یافت نشد."
                    BackendException.Reason.SERVICE_NOT_RUNNING ->
                        "سرویس تونل در حال اجرا نیست."
                    else ->
                        "خطای هسته (${err.reason}${extra?.let { ": $it" }.orEmpty()})."
                }
            }
            is BadConfigException -> {
                val detail = err.text?.toString()?.takeIf { it.isNotBlank() } ?: ""
                "خطا در فایل کانفیگ [${err.section}/${err.location}]: ${err.reason} $detail".trim()
            }
            is PackageManager.NameNotFoundException ->
                "بسته Split-Tunnel روی دستگاه نصب نیست: ${err.message.orEmpty()}"
            else ->
                err.message?.takeIf { it.isNotBlank() }
                    ?: "${err.javaClass.simpleName}: ${err.cause?.message ?: "خطای ناشناخته"}"
        }
    }

    private fun startTelemetryLoop(tunnel: CodmTunnel, endpointLabel: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var prevRx = 0L
            var prevTx = 0L
            while (isActive) {
                runCatching {
                    val liveStats = backend.getStatistics(tunnel)
                    val totalRx = liveStats.totalRx()
                    val totalTx = liveStats.totalTx()
                    val deltaRx = (totalRx - prevRx).coerceAtLeast(0L)
                    val deltaTx = (totalTx - prevTx).coerceAtLeast(0L)
                    prevRx = totalRx
                    prevTx = totalTx

                    val rxKbps = (deltaRx * 8.0) / 1000.0
                    val txKbps = (deltaTx * 8.0) / 1000.0
                    val uptime = if (connectedAtMillis > 0L) {
                        (System.currentTimeMillis() - connectedAtMillis) / 1000L
                    } else 0L

                    _stats.value = _stats.value.copy(
                        state = TunnelState.CONNECTED,
                        rxBytes = totalRx,
                        txBytes = totalTx,
                        rxSpeedKbps = rxKbps,
                        txSpeedKbps = txKbps,
                        uptimeSeconds = uptime,
                        endpoint = endpointLabel,
                        isStale = liveStats.isStale
                    )

                    NativeBridge.recordNetworkSample(
                        rttMs = if (liveStats.isStale) 99.0 else 0.0,
                        rxBytes = totalRx,
                        txBytes = totalTx
                    )
                }
                delay(1000L)
            }
        }
    }
}
