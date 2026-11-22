package com.example.bridge

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode {
    AMNEZIA_WG,
    DOH_TURBO
}

enum class ThermalLevel(val code: Int, val labelFa: String) {
    UNSUPPORTED(-1, "نامشخص"),
    NONE(0, "خنک / ایده‌آل"),
    LIGHT(1, "گرمای خفیف"),
    MODERATE(2, "متوسط"),
    SEVERE(3, "داغ (کاهش کلاک)"),
    CRITICAL(4, "بحرانی"),
    EMERGENCY(5, "اضطراری"),
    SHUTDOWN(6, "خاموشی حرارتی");

    companion object {
        fun fromCode(code: Int): ThermalLevel =
            entries.firstOrNull { it.code == code } ?: UNSUPPORTED
    }
}

data class HardwareTelemetry(
    val apiLevel: Int = 0,
    val thermalLevel: ThermalLevel = ThermalLevel.UNSUPPORTED,
    val thermalHeadroom5s: Float = -1f,
    val memTotalMb: Double = 0.0,
    val memAvailableMb: Double = 0.0,
    val memUsagePercent: Double = 0.0,
    val adpfSupported: Boolean = false,
    val adpfSessionActive: Boolean = false,
    val adpfPreferredRateNanos: Long = -1L,
    val avgFrameTimeMs: Double = 0.0,
    val frameJitterMs: Double = 0.0,
    val p99FrameTimeMs: Double = 0.0,
    val avgNetRttMs: Double = 0.0,
    val netJitterMs: Double = 0.0,
    val onlineCpuCores: Int = 1,
    val currentTid: Int = 0
)

enum class TunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class TunnelStats(
    val state: TunnelState = TunnelState.DISCONNECTED,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxSpeedKbps: Double = 0.0,
    val txSpeedKbps: Double = 0.0,
    val uptimeSeconds: Long = 0L,
    val endpoint: String = "",
    val isStale: Boolean = false,
    val errorMessage: String? = null
)

@Serializable
data class DohEngineStats(
    val running: Boolean = false,
    val activeUrl: String = "",
    val totalQueries: Long = 0L,
    val cacheHits: Long = 0L,
    val cacheMisses: Long = 0L,
    val failedQueries: Long = 0L,
    val cacheEntries: Int = 0,
    val lastRttMs: Double = 0.0,
    val avgRttMs: Double = 0.0
) {
    val cacheHitRatePercent: Int
        get() = if (totalQueries > 0L) ((cacheHits * 100L) / totalQueries).toInt() else 0
}

@Serializable
data class DohBenchmarkItem(
    val url: String = "",
    val reachable: Boolean = false,
    val avgRttMs: Double = 0.0,
    val minRttMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val httpStatus: Int = 0
)

@Serializable
data class EndpointBenchmarkResult(
    val endpoint: String = "",
    val reachable: Boolean = false,
    val avgRttMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val probeMethod: String = ""
)

@Serializable
data class BenchmarkReport(
    val dohResults: List<DohBenchmarkItem> = emptyList(),
    val endpointResult: EndpointBenchmarkResult? = null,
    val bestDohUrl: String? = null
)

data class ShizukuStatus(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val uid: Int = -1
)

data class BoostExecutionResult(
    val adpfActive: Boolean = false,
    val dndEnabled: Boolean = false,
    val shizukuApplied: Boolean = false,
    val codmLaunched: Boolean = false,
    val logs: List<String> = emptyList()
)
