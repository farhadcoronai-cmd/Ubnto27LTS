package com.example.bridge

import kotlinx.serialization.Serializable

@Serializable
data class DohProvider(
    val id: String,
    val name: String,
    val url: String,
    val badge: String,
    val isCustom: Boolean = false
)

object DefaultDohProviders {
    val presets: List<DohProvider> = listOf(
        DohProvider(
            id = "cloudflare_gaming",
            name = "Cloudflare Edge (1.1.1.1)",
            url = "https://1.1.1.1/dns-query",
            badge = "ULTRA-LOW RTT"
        ),
        DohProvider(
            id = "google_doh",
            name = "Google Public DoH",
            url = "https://dns.google/dns-query",
            badge = "ANYCAST HTTP/2"
        ),
        DohProvider(
            id = "quad9_doh",
            name = "Quad9 Secured (9.9.9.9)",
            url = "https://dns.quad9.net/dns-query",
            badge = "THREAT BLOCK"
        ),
        DohProvider(
            id = "cloudflare_malware",
            name = "Cloudflare Security (1.1.1.2)",
            url = "https://1.1.1.2/dns-query",
            badge = "SAFE GAMING"
        ),
        DohProvider(
            id = "adguard_unfiltered",
            name = "AdGuard Unfiltered DoH",
            url = "https://unfiltered.adguard-dns.com/dns-query",
            badge = "NO FILTERING"
        )
    )
}
