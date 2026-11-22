package com.example.bridge

import kotlinx.serialization.Serializable

@Serializable
data class HeaderSpec(
    val raw: String = "",
    val min: Long = 0L,
    val max: Long = 0L,
    val isRange: Boolean = false
)

@Serializable
data class AwgParameters(
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    val s1: Int? = null,
    val s2: Int? = null,
    val s3: Int? = null,
    val s4: Int? = null,
    val h1: HeaderSpec? = null,
    val h2: HeaderSpec? = null,
    val h3: HeaderSpec? = null,
    val h4: HeaderSpec? = null,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null
)

@Serializable
data class AwgSummary(
    val hasPrivateKey: Boolean = false,
    val hasPublicKey: Boolean = false,
    val endpoint: String? = null,
    val mtu: Int? = null,
    val persistentKeepalive: Int? = null,
    val addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val allowedIps: List<String> = emptyList(),
    val includedApplications: List<String> = emptyList(),
    val excludedApplications: List<String> = emptyList(),
    val isAmneziaWg: Boolean = false,
    val awgVersionHint: String = "Standard WireGuard",
    val awgParams: AwgParameters = AwgParameters()
)

@Serializable
data class AwgValidationResult(
    val valid: Boolean = false,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val summary: AwgSummary = AwgSummary()
)

@Serializable
data class AwgOptimizeResult(
    val success: Boolean = false,
    val optimizedConfig: String = "",
    val appliedMtu: Int = 1280,
    val appliedKeepalive: Int = 25,
    val splitTunnelCodmApplied: Boolean = false,
    val changes: List<String> = emptyList(),
    val validation: AwgValidationResult = AwgValidationResult()
)

@Serializable
data class AwgProfile(
    val id: String,
    val name: String,
    val rawConfig: String,
    val isOptimizedForCodm: Boolean = false,
    val splitTunnelCodm: Boolean = true,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
