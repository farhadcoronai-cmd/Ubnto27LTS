package com.example.bridge

import android.util.Base64
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object NativeBridge {
    private const val TAG = "NativeBridge"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    var isCppLoaded: Boolean = false
        private set

    @Volatile
    var isRustLoaded: Boolean = false
        private set

    init {
        isCppLoaded = try {
            System.loadLibrary("codm_native_booster")
            true
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "libcodm_native_booster.so unavailable: ${t.message}")
            false
        }

        isRustLoaded = try {
            System.loadLibrary("codm_rust_core")
            true
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "libcodm_rust_core.so not bundled yet (run cargo-ndk in CI): ${t.message}")
            false
        }
    }

    // --- C++20 NDK Native Methods ---
    private external fun nativeInitEngine(): Boolean
    private external fun nativeShutdownEngine()
    private external fun nativeStartAdpfSession(targetFps: Int, threadIds: IntArray): Boolean
    private external fun nativeUpdateAdpfTargetFps(targetFps: Int): Boolean
    private external fun nativeReportAdpfWorkDuration(actualDurationNanos: Long): Boolean
    private external fun nativeStopAdpfSession()
    private external fun nativeRecordFrameTimeNanos(durationNanos: Long)
    private external fun nativeRecordNetworkSample(rttMs: Double, rxBytes: Long, txBytes: Long)
    private external fun nativeGetCurrentTid(): Int
    private external fun nativeCollectTelemetry(): DoubleArray

    // --- Rust Core Native Methods ---
    private external fun nativeValidateAndParseAwgConfig(configText: String): String
    private external fun nativeOptimizeAwgConfigForCodm(
        configText: String,
        enforceSplitTunnel: Boolean
    ): String
    private external fun nativeStartDohEngine(tunFd: Int, dohUrl: String): Boolean
    private external fun nativeStopDohEngine()
    private external fun nativeUpdateDohUrl(dohUrl: String)
    private external fun nativeGetDohStatsJson(): String
    private external fun nativeBenchmarkDohAndEndpointJson(
        dohUrlsJson: String,
        endpointHostPort: String
    ): String

    // --- Safe Kotlin API Wrappers ---

    fun initCppEngine(): Boolean =
        if (isCppLoaded) runCatching { nativeInitEngine() }.getOrDefault(false) else false

    fun shutdownCppEngine() {
        if (isCppLoaded) runCatching { nativeShutdownEngine() }
    }

    fun startAdpfSession(targetFps: Int, threadIds: IntArray): Boolean =
        if (isCppLoaded) runCatching { nativeStartAdpfSession(targetFps, threadIds) }.getOrDefault(false)
        else false

    fun updateAdpfTargetFps(targetFps: Int): Boolean =
        if (isCppLoaded) runCatching { nativeUpdateAdpfTargetFps(targetFps) }.getOrDefault(false)
        else false

    fun reportAdpfWorkDuration(actualDurationNanos: Long): Boolean =
        if (isCppLoaded) runCatching { nativeReportAdpfWorkDuration(actualDurationNanos) }.getOrDefault(false)
        else false

    fun stopAdpfSession() {
        if (isCppLoaded) runCatching { nativeStopAdpfSession() }
    }

    fun recordFrameTimeNanos(durationNanos: Long) {
        if (isCppLoaded) runCatching { nativeRecordFrameTimeNanos(durationNanos) }
    }

    fun recordNetworkSample(rttMs: Double, rxBytes: Long, txBytes: Long) {
        if (isCppLoaded) runCatching { nativeRecordNetworkSample(rttMs, rxBytes, txBytes) }
    }

    fun getCurrentTid(): Int =
        if (isCppLoaded) runCatching { nativeGetCurrentTid() }.getOrDefault(0) else 0

    fun collectHardwareTelemetry(): HardwareTelemetry {
        if (!isCppLoaded) return HardwareTelemetry()
        val arr = runCatching { nativeCollectTelemetry() }.getOrNull() ?: return HardwareTelemetry()
        if (arr.size < 16) return HardwareTelemetry()
        return HardwareTelemetry(
            apiLevel = arr[0].toInt(),
            thermalLevel = ThermalLevel.fromCode(arr[1].toInt()),
            thermalHeadroom5s = arr[2].toFloat(),
            memTotalMb = arr[3],
            memAvailableMb = arr[4],
            memUsagePercent = arr[5],
            adpfSupported = arr[6] >= 0.5,
            adpfSessionActive = arr[7] >= 0.5,
            adpfPreferredRateNanos = arr[8].toLong(),
            avgFrameTimeMs = arr[9],
            frameJitterMs = arr[10],
            p99FrameTimeMs = arr[11],
            avgNetRttMs = arr[12],
            netJitterMs = arr[13],
            onlineCpuCores = arr[14].toInt().coerceAtLeast(1),
            currentTid = arr[15].toInt()
        )
    }

    fun validateAwgConfig(configText: String): AwgValidationResult {
        if (isRustLoaded) {
            val rawJson = runCatching { nativeValidateAndParseAwgConfig(configText) }.getOrNull()
            if (!rawJson.isNullOrBlank()) {
                runCatching { return json.decodeFromString<AwgValidationResult>(rawJson) }
            }
        }
        return fallbackValidateAwgConfig(configText)
    }

    fun optimizeAwgConfigForCodm(
        configText: String,
        enforceSplitTunnel: Boolean
    ): AwgOptimizeResult {
        if (isRustLoaded) {
            val rawJson = runCatching {
                nativeOptimizeAwgConfigForCodm(configText, enforceSplitTunnel)
            }.getOrNull()
            if (!rawJson.isNullOrBlank()) {
                runCatching { return json.decodeFromString<AwgOptimizeResult>(rawJson) }
            }
        }
        return fallbackOptimizeAwgConfig(configText, enforceSplitTunnel)
    }

    fun startDohEngine(tunFd: Int, dohUrl: String): Boolean =
        if (isRustLoaded) runCatching { nativeStartDohEngine(tunFd, dohUrl) }.getOrDefault(false)
        else false

    fun stopDohEngine() {
        if (isRustLoaded) runCatching { nativeStopDohEngine() }
    }

    fun updateDohUrl(dohUrl: String) {
        if (isRustLoaded) runCatching { nativeUpdateDohUrl(dohUrl) }
    }

    fun getDohStats(): DohEngineStats {
        if (!isRustLoaded) return DohEngineStats()
        val rawJson = runCatching { nativeGetDohStatsJson() }.getOrNull() ?: return DohEngineStats()
        return runCatching { json.decodeFromString<DohEngineStats>(rawJson) }
            .getOrDefault(DohEngineStats())
    }

    fun benchmarkDohAndEndpoint(
        dohUrls: List<String>,
        endpointHostPort: String
    ): BenchmarkReport {
        if (isRustLoaded) {
            val urlsJson = json.encodeToString(dohUrls)
            val rawJson = runCatching {
                nativeBenchmarkDohAndEndpointJson(urlsJson, endpointHostPort)
            }.getOrNull()
            if (!rawJson.isNullOrBlank()) {
                runCatching { return json.decodeFromString<BenchmarkReport>(rawJson) }
            }
        }
        return fallbackBenchmark(dohUrls, endpointHostPort)
    }

    // --- Pure Kotlin Fallback (used only if libcodm_rust_core.so is absent in local preview) ---

    private fun fallbackValidateAwgConfig(rawText: String): AwgValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val iface = linkedMapOf<String, String>()
        val peer = linkedMapOf<String, String>()
        var section = ""

        rawText.lines().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) return@forEachIndexed
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                return@forEachIndexed
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) {
                errors += "Line ${idx + 1}: Expected Key = Value syntax"
                return@forEachIndexed
            }
            val k = trimmed.substring(0, eqIdx).trim().lowercase()
            val v = trimmed.substring(eqIdx + 1).trim()
            when (section) {
                "interface" -> iface[k] = v
                "peer" -> peer[k] = v
                else -> errors += "Line ${idx + 1}: Key '$k' outside [Interface] or [Peer]"
            }
        }

        val privKey = iface["privatekey"]
        if (privKey.isNullOrBlank()) errors += "Missing [Interface] PrivateKey"
        else if (!isValidBase64Key(privKey)) errors += "Invalid [Interface] PrivateKey (expected 32-byte Base64)"

        val pubKey = peer["publickey"]
        if (pubKey.isNullOrBlank()) errors += "Missing [Peer] PublicKey"
        else if (!isValidBase64Key(pubKey)) errors += "Invalid [Peer] PublicKey (expected 32-byte Base64)"

        val addrs = iface["address"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (addrs.isEmpty()) errors += "Missing [Interface] Address"

        val aips = peer["allowedips"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (aips.isEmpty()) errors += "Missing [Peer] AllowedIPs"

        val s1 = iface["s1"]?.toIntOrNull()
        val s2 = iface["s2"]?.toIntOrNull()
        val s3 = iface["s3"]?.toIntOrNull()
        val s4 = iface["s4"]?.toIntOrNull()
        if (s1 != null && s2 != null && s1 + 56 == s2) {
            errors += "AmneziaWG constraint violated: S1 + 56 must not equal S2"
        }

        val jc = iface["jc"]?.toIntOrNull()
        val jmin = iface["jmin"]?.toIntOrNull()
        val jmax = iface["jmax"]?.toIntOrNull()
        if (jmin != null && jmax != null && jmin > jmax) {
            errors += "AmneziaWG Jmin ($jmin) cannot exceed Jmax ($jmax)"
        }

        val h1 = parseHeader(iface["h1"])
        val h2 = parseHeader(iface["h2"])
        val h3 = parseHeader(iface["h3"])
        val h4 = parseHeader(iface["h4"])
        val i1 = iface["i1"]
        val i2 = iface["i2"]
        val i3 = iface["i3"]
        val i4 = iface["i4"]
        val i5 = iface["i5"]

        val hasV2 = s3 != null || s4 != null || listOf(h1, h2, h3, h4).any { it?.isRange == true }
        val hasV15 = listOf(i1, i2, i3, i4, i5).any { !it.isNullOrBlank() }
        val hasAwg = hasV2 || hasV15 || listOf(jc, jmin, jmax, s1, s2).any { it != null } || h1 != null

        val ka = peer["persistentkeepalive"]?.toIntOrNull()
        if (ka == null || ka == 0 || ka > 30) {
            warnings += "PersistentKeepalive 25s recommended for mobile CGNAT stability"
        }

        return AwgValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            summary = AwgSummary(
                hasPrivateKey = !privKey.isNullOrBlank(),
                hasPublicKey = !pubKey.isNullOrBlank(),
                endpoint = peer["endpoint"],
                mtu = iface["mtu"]?.toIntOrNull(),
                persistentKeepalive = ka,
                addresses = addrs,
                dnsServers = iface["dns"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                allowedIps = aips,
                includedApplications = iface["includedapplications"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                excludedApplications = iface["excludedapplications"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                isAmneziaWg = hasAwg,
                awgVersionHint = when {
                    hasV2 -> "AmneziaWG 2.0"
                    hasV15 -> "AmneziaWG 1.5 (CPS)"
                    hasAwg -> "AmneziaWG 1.0"
                    else -> "Standard WireGuard"
                },
                awgParams = AwgParameters(
                    jc = jc, jmin = jmin, jmax = jmax,
                    s1 = s1, s2 = s2, s3 = s3, s4 = s4,
                    h1 = h1, h2 = h2, h3 = h3, h4 = h4,
                    i1 = i1, i2 = i2, i3 = i3, i4 = i4, i5 = i5
                )
            )
        )
    }

    private fun fallbackOptimizeAwgConfig(
        rawText: String,
        enforceSplitTunnel: Boolean
    ): AwgOptimizeResult {
        val initial = fallbackValidateAwgConfig(rawText)
        val s1 = initial.summary.awgParams.s1 ?: 0
        val s2 = initial.summary.awgParams.s2 ?: 0
        val s4 = initial.summary.awgParams.s4 ?: 0
        val pad = maxOf(s4, s1 / 2, s2 / 4).coerceAtMost(80)
        val targetMtu = (1360 - pad).coerceIn(1280, 1360)
        val changes = mutableListOf<String>()

        val out = mutableListOf<String>()
        var inInterface = false
        var inPeer = false
        var mtuWritten = false
        var incWritten = false
        var kaWritten = false

        fun flushInterface() {
            if (!mtuWritten) {
                out += "MTU = $targetMtu"
                mtuWritten = true
                changes += "Tuned MTU to $targetMtu"
            }
            if (enforceSplitTunnel && !incWritten) {
                out += "IncludedApplications = com.activision.callofduty.shooter"
                incWritten = true
                changes += "Locked split-tunnel to CODM"
            }
        }

        for (line in rawText.lines()) {
            val trimmed = line.trim()
            if (trimmed.equals("[Interface]", ignoreCase = true)) {
                inInterface = true
                inPeer = false
                out += line
                continue
            }
            if (trimmed.equals("[Peer]", ignoreCase = true)) {
                if (inInterface) flushInterface()
                inInterface = false
                inPeer = true
                out += line
                continue
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0 && !trimmed.startsWith("#")) {
                val k = trimmed.substring(0, eqIdx).trim().lowercase()
                if (inInterface && k == "mtu") {
                    out += "MTU = $targetMtu"
                    mtuWritten = true
                    changes += "Tuned MTU to $targetMtu"
                    continue
                }
                if (inInterface && enforceSplitTunnel && k == "includedapplications") {
                    out += "IncludedApplications = com.activision.callofduty.shooter"
                    incWritten = true
                    changes += "Locked split-tunnel to CODM"
                    continue
                }
                if (inInterface && enforceSplitTunnel && k == "excludedapplications") {
                    continue
                }
                if (inPeer && k == "persistentkeepalive") {
                    out += "PersistentKeepalive = 25"
                    kaWritten = true
                    changes += "Enforced PersistentKeepalive = 25s"
                    continue
                }
            }
            out += line
        }
        if (inInterface) flushInterface()
        if (inPeer && !kaWritten) {
            out += "PersistentKeepalive = 25"
            changes += "Added PersistentKeepalive = 25s"
        }

        val optimized = out.joinToString("\n").trimEnd() + "\n"
        val finalValidation = fallbackValidateAwgConfig(optimized)
        return AwgOptimizeResult(
            success = finalValidation.valid,
            optimizedConfig = optimized,
            appliedMtu = targetMtu,
            appliedKeepalive = 25,
            splitTunnelCodmApplied = enforceSplitTunnel,
            changes = changes,
            validation = finalValidation
        )
    }

    private fun fallbackBenchmark(
        dohUrls: List<String>,
        endpointHostPort: String
    ): BenchmarkReport {
        val client = OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
        val dnsQuery = byteArrayOf(
            0xC0.toByte(), 0xD1.toByte(), 0x01, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x04, 'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(),
            0x04, 'c'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'm'.code.toByte(),
            0x0A, 'a'.code.toByte(), 'c'.code.toByte(), 't'.code.toByte(), 'i'.code.toByte(),
            'v'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'i'.code.toByte(),
            'o'.code.toByte(), 'n'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01
        )
        val mediaType = "application/dns-message".toMediaType()
        val items = dohUrls.map { url ->
            val rtts = mutableListOf<Double>()
            var status = 0
            repeat(2) {
                val startNs = System.nanoTime()
                runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/dns-message")
                        .post(dnsQuery.toRequestBody(mediaType))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        status = resp.code
                        if (resp.isSuccessful) {
                            resp.body?.bytes()
                            rtts += (System.nanoTime() - startNs) / 1_000_000.0
                        }
                    }
                }
            }
            if (rtts.isEmpty()) {
                DohBenchmarkItem(url = url, reachable = false, httpStatus = status)
            } else {
                val avg = rtts.average()
                val jitter = if (rtts.size > 1) abs(rtts[1] - rtts[0]) else 0.0
                DohBenchmarkItem(
                    url = url,
                    reachable = true,
                    avgRttMs = ((avg * 100.0).roundToInt() / 100.0),
                    minRttMs = ((rtts.min() * 100.0).roundToInt() / 100.0),
                    jitterMs = ((jitter * 100.0).roundToInt() / 100.0),
                    httpStatus = status
                )
            }
        }.sortedWith(compareBy<DohBenchmarkItem> { !it.reachable }.thenBy { it.avgRttMs })

        val epResult = endpointHostPort.trim().takeIf { it.isNotEmpty() }?.let { ep ->
            val lastColon = ep.lastIndexOf(':')
            val host = if (lastColon > 0) ep.substring(0, lastColon).trim('[', ']') else ep
            val port = if (lastColon > 0) ep.substring(lastColon + 1).toIntOrNull() ?: 51820 else 51820
            val epRtts = mutableListOf<Double>()
            repeat(3) {
                val start = System.nanoTime()
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, port), 1200)
                        epRtts += (System.nanoTime() - start) / 1_000_000.0
                    }
                } catch (e: java.net.ConnectException) {
                    if (e.message?.contains("ECONNREFUSED", ignoreCase = true) == true) {
                        epRtts += (System.nanoTime() - start) / 1_000_000.0
                    }
                } catch (_: Exception) {
                }
            }
            if (epRtts.isEmpty()) {
                EndpointBenchmarkResult(endpoint = ep, reachable = false, probeMethod = "TCP RST Fallback")
            } else {
                val jitter = if (epRtts.size > 1) {
                    epRtts.zipWithNext { a, b -> abs(b - a) }.average()
                } else 0.0
                EndpointBenchmarkResult(
                    endpoint = ep,
                    reachable = true,
                    avgRttMs = ((epRtts.average() * 100.0).roundToInt() / 100.0),
                    jitterMs = ((jitter * 100.0).roundToInt() / 100.0),
                    probeMethod = "TCP Path Probe"
                )
            }
        }

        return BenchmarkReport(
            dohResults = items,
            endpointResult = epResult,
            bestDohUrl = items.firstOrNull { it.reachable }?.url
        )
    }

    private fun isValidBase64Key(raw: String): Boolean =
        runCatching {
            val decoded = Base64.decode(raw.trim(), Base64.DEFAULT)
            decoded.size == 32 && decoded.any { it != 0.toByte() }
        }.getOrDefault(false)

    private fun parseHeader(raw: String?): HeaderSpec? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.contains('-')) {
            val parts = trimmed.split('-', limit = 2)
            val min = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
            val max = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
            HeaderSpec(raw = trimmed, min = min, max = max, isRange = true)
        } else {
            val v = trimmed.toLongOrNull() ?: return null
            HeaderSpec(raw = trimmed, min = v, max = v, isRange = false)
        }
    }
}
