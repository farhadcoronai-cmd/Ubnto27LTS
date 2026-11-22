package com.example.vpn

import android.content.Context
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
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
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
        runCatching {
            _stats.value = _stats.value.copy(
                state = TunnelState.CONNECTING,
                errorMessage = null
            )

            val preparedConfig = if (enforceCodmSplitTunnel) {
                NativeBridge.optimizeAwgConfigForCodm(rawConfigText, true).optimizedConfig
            } else {
                rawConfigText
            }

            val parsedConfig = Config.parse(BufferedReader(StringReader(preparedConfig)))
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
        }.onFailure { err ->
            _stats.value = TunnelStats(
                state = TunnelState.ERROR,
                errorMessage = err.message ?: "Failed to start AmneziaWG tunnel"
            )
        }
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
                        rttMs = _stats.value.let { if (it.isStale) 99.0 else 0.0 },
                        rxBytes = totalRx,
                        txBytes = totalTx
                    )
                }
                delay(1000L)
            }
        }
    }
}
