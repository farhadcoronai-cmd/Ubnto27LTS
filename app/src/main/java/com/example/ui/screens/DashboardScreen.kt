package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.bridge.RoutingMode
import com.example.bridge.ThermalLevel
import com.example.bridge.TunnelState
import com.example.ui.BoosterUiState
import com.example.ui.components.HeroMetricPill
import com.example.ui.components.NetworkTelemetryStrip
import com.example.ui.components.RttHistorySparkline
import com.example.ui.components.SpringMetricGauge
import com.example.ui.components.TacticalRadarSweep
import com.example.ui.theme.TabularMetricSmall
import java.util.Locale

@Composable
fun DashboardScreen(
    uiState: BoosterUiState,
    onPrepareVpnIntent: () -> android.content.Intent?,
    onSetRoutingMode: (RoutingMode) -> Unit,
    onToggleTunnel: () -> Unit,
    onToggleSplitTunnel: (Boolean) -> Unit,
    onToggleDndAuto: (Boolean) -> Unit,
    onOpenDndSettings: () -> Unit,
    onToggleShizukuPro: (Boolean) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onSelectTargetFps: (Int) -> Unit,
    onEngageBoost: (Boolean) -> Unit,
    onDisengageBoost: () -> Unit,
    onOpenVaultTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onToggleTunnel()
        }
    }

    val handleTunnelClick = {
        if (uiState.isTunnelActive || uiState.isTunnelConnecting) {
            onToggleTunnel()
        } else {
            val prepIntent = onPrepareVpnIntent()
            if (prepIntent != null) {
                vpnPermissionLauncher.launch(prepIntent)
            } else {
                onToggleTunnel()
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 700.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = if (isExpanded) 960.dp else 600.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Tactical Header
            item {
                TacticalHeaderCard(uiState = uiState)
            }

            // 2. Routing Mode Switcher
            item {
                RoutingModeSelector(
                    uiState = uiState,
                    onSetRoutingMode = onSetRoutingMode,
                    onOpenVaultTab = onOpenVaultTab
                )
            }

            // 3. Radar & Telemetry (Responsive 2-column on tablets, stacked on phones)
            item {
                if (isExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadarCommandPanel(
                            uiState = uiState,
                            modifier = Modifier.weight(0.9f)
                        )
                        Column(
                            modifier = Modifier.weight(1.1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HardwareGaugesRow(uiState = uiState)
                            NetworkTelemetryStrip(
                                routingMode = uiState.settings.routingMode,
                                tunnelStats = uiState.tunnelStats,
                                dohStats = uiState.dohStats
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadarCommandPanel(uiState = uiState)
                        HardwareGaugesRow(uiState = uiState)
                        NetworkTelemetryStrip(
                            routingMode = uiState.settings.routingMode,
                            tunnelStats = uiState.tunnelStats,
                            dohStats = uiState.dohStats
                        )
                    }
                }
            }

            // 4. RTT Sparkline (shown when live samples exist)
            if (uiState.rttHistory.size >= 2) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "روند نوسان پینگ (RTT Trend)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${uiState.rttHistory.last().toInt()} ms",
                                    style = TabularMetricSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            RttHistorySparkline(
                                samplesMs = uiState.rttHistory,
                                accentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 5. Tactical Optimization Controls (Split Tunnel, DND, Shizuku Pro, FPS)
            item {
                TacticalControlsCard(
                    uiState = uiState,
                    onToggleSplitTunnel = onToggleSplitTunnel,
                    onToggleDndAuto = onToggleDndAuto,
                    onOpenDndSettings = onOpenDndSettings,
                    onToggleShizukuPro = onToggleShizukuPro,
                    onRequestShizukuPermission = onRequestShizukuPermission,
                    onSelectTargetFps = onSelectTargetFps
                )
            }

            // 6. Primary Action Buttons: Connect Tunnel + Engage Boost & Launch CODM
            item {
                PrimaryActionCluster(
                    uiState = uiState,
                    onToggleTunnel = handleTunnelClick,
                    onEngageBoost = onEngageBoost,
                    onDisengageBoost = onDisengageBoost,
                    codmInstalled = uiState.installedCodmPackage != null ||
                        context.packageManager != null
                )
            }

            // 7. Boost Execution Logs
            if (uiState.boostLogs.isNotEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "گزارش اجرای موتور بهینه‌سازی:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            uiState.boostLogs.forEach { log ->
                                Text(
                                    text = "▸ $log",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TacticalHeaderCard(uiState: BoosterUiState) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.codm_stealth_icon_1795373979702),
                    contentDescription = "نشان CODM Stealth",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "CODM STEALTH OPS",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = if (uiState.installedCodmPackage != null)
                            "CODM شناسایی شد • ضدتقلب ایمن"
                        else
                            "آماده اتصال • ضدتقلب بدون تزریق حافظه",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (uiState.isCppLoaded) "NDK C++20" else "NDK OFF",
                    style = TabularMetricSmall.copy(fontSize = 10.sp),
                    color = if (uiState.isCppLoaded) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (uiState.isRustLoaded) "RUST CORE" else "KOTLIN FALLBACK",
                    style = TabularMetricSmall.copy(fontSize = 10.sp),
                    color = if (uiState.isRustLoaded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun RoutingModeSelector(
    uiState: BoosterUiState,
    onSetRoutingMode: (RoutingMode) -> Unit,
    onOpenVaultTab: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("routing_mode_switcher")
            ) {
                SegmentedButton(
                    selected = uiState.settings.routingMode == RoutingMode.DOH_TURBO,
                    onClick = { onSetRoutingMode(RoutingMode.DOH_TURBO) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Rust DoH Turbo") }
                )
                SegmentedButton(
                    selected = uiState.settings.routingMode == RoutingMode.AMNEZIA_WG,
                    onClick = { onSetRoutingMode(RoutingMode.AMNEZIA_WG) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("AmneziaWG Stealth") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeTargetText = when (uiState.settings.routingMode) {
                    RoutingMode.DOH_TURBO ->
                        "فقط DNS رمزنگاری‌شده (${uiState.settings.selectedDohUrl.removePrefix("https://")})"
                    RoutingMode.AMNEZIA_WG ->
                        uiState.activeProfile?.let { "پروفایل فعال: ${it.name}" }
                            ?: "پروفایلی انتخاب نشده — از مخزن وارد کنید"
                }
                Text(
                    text = activeTargetText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onOpenVaultTab,
                    modifier = Modifier.testTag("open_vault_shortcut")
                ) {
                    Text("تنظیم")
                }
            }
        }
    }
}

@Composable
private fun RadarCommandPanel(
    uiState: BoosterUiState,
    modifier: Modifier = Modifier
) {
    val isError = uiState.settings.routingMode == RoutingMode.AMNEZIA_WG &&
        uiState.tunnelStats.state == TunnelState.ERROR

    val statusLabel = when {
        uiState.isTunnelConnecting -> "در حال همگام‌سازی"
        uiState.isTunnelActive -> "سپر شبکه فعال"
        isError -> "خطای اتصال"
        else -> "آماده‌باش تاکتیکی"
    }

    val subLabel = when {
        uiState.isTunnelActive && uiState.settings.routingMode == RoutingMode.DOH_TURBO ->
            "DoH HTTP/2 • ${uiState.dohStats.cacheHitRatePercent}% HIT"
        uiState.isTunnelActive && uiState.settings.routingMode == RoutingMode.AMNEZIA_WG ->
            "AWG UP • ${uiState.tunnelStats.uptimeSeconds}s"
        else ->
            "${uiState.hardware.onlineCpuCores} CORES • API ${uiState.hardware.apiLevel}"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TacticalRadarSweep(
                isActive = uiState.isTunnelActive,
                isConnecting = uiState.isTunnelConnecting,
                isError = isError,
                statusLabel = statusLabel,
                subLabel = subLabel,
                shockwaveKey = uiState.shockwaveKey
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val liveRtt = when {
                    uiState.dohRunning && uiState.dohStats.avgRttMs > 0.0 ->
                        "${uiState.dohStats.avgRttMs.toInt()} ms"
                    uiState.hardware.avgNetRttMs > 0.0 ->
                        "${uiState.hardware.avgNetRttMs.toInt()} ms"
                    else -> "--"
                }
                HeroMetricPill(
                    label = "تأخیر DNS/Net",
                    value = liveRtt,
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                HeroMetricPill(
                    label = "نوسان Jitter",
                    value = String.format(Locale.US, "%.1f ms", uiState.hardware.netJitterMs),
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                HeroMetricPill(
                    label = "ADPF هدف",
                    value = "${uiState.settings.targetFps} FPS",
                    accentColor = if (uiState.hardware.adpfSessionActive)
                        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HardwareGaugesRow(uiState: BoosterUiState) {
    val hw = uiState.hardware
    val thermalProgress = when (hw.thermalLevel) {
        ThermalLevel.UNSUPPORTED -> 0.2f
        ThermalLevel.NONE -> 0.22f
        ThermalLevel.LIGHT -> 0.42f
        ThermalLevel.MODERATE -> 0.62f
        ThermalLevel.SEVERE -> 0.80f
        ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN -> 0.96f
    }
    val thermalColor = when (hw.thermalLevel) {
        ThermalLevel.NONE, ThermalLevel.LIGHT -> MaterialTheme.colorScheme.tertiary
        ThermalLevel.MODERATE -> MaterialTheme.colorScheme.secondary
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN ->
            MaterialTheme.colorScheme.error
        ThermalLevel.UNSUPPORTED -> MaterialTheme.colorScheme.primary
    }

    val ramPercent = (hw.memUsagePercent / 100.0).toFloat().coerceIn(0f, 1f)
    val ramAvailGb = hw.memAvailableMb / 1024.0
    val jitterNorm = (hw.netJitterMs / 40.0).toFloat().coerceIn(0.05f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SpringMetricGauge(
            title = "وضعیت حرارت",
            valueText = if (hw.thermalHeadroom5s >= 0f)
                String.format(Locale.US, "%.0f%%", (hw.thermalHeadroom5s * 100f).coerceAtMost(120f))
            else hw.thermalLevel.code.let { if (it >= 0) "LVL $it" else "OK" },
            unit = "THERMAL",
            progress = thermalProgress,
            accentColor = thermalColor,
            subtitle = hw.thermalLevel.labelFa,
            modifier = Modifier.weight(1f)
        )

        SpringMetricGauge(
            title = "مصرف رم",
            valueText = "${hw.memUsagePercent.toInt()}%",
            unit = "RAM",
            progress = ramPercent,
            accentColor = MaterialTheme.colorScheme.primary,
            subtitle = String.format(Locale.US, "%.1f GB آزاد", ramAvailGb),
            modifier = Modifier.weight(1f)
        )

        SpringMetricGauge(
            title = "جیتر شبکه",
            valueText = String.format(Locale.US, "%.1f", hw.netJitterMs),
            unit = "MS",
            progress = jitterNorm,
            accentColor = MaterialTheme.colorScheme.secondary,
            subtitle = if (hw.adpfSessionActive) "ADPF فعال" else "آماده بوست",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TacticalControlsCard(
    uiState: BoosterUiState,
    onToggleSplitTunnel: (Boolean) -> Unit,
    onToggleDndAuto: (Boolean) -> Unit,
    onOpenDndSettings: () -> Unit,
    onToggleShizukuPro: (Boolean) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onSelectTargetFps: (Int) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Split Tunnel Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "تونل اختصاصی CODM (Split-Tunnel)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "عبور انحصاری ترافیک Call of Duty: Mobile از AmneziaWG",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = uiState.settings.codmSplitTunnelEnabled,
                    onCheckedChange = onToggleSplitTunnel,
                    modifier = Modifier.testTag("split_tunnel_switch")
                )
            }

            // DND Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DoNotDisturbOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "مزاحم نشوید خودکار (DND)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!uiState.hasDndAccess) {
                            TextButton(
                                onClick = onOpenDndSettings,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = "نیاز به مجوز DND — اعطای دسترسی",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        } else {
                            Text(
                                text = "قطع اعلان‌های مزاحم حین مچ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Switch(
                    checked = uiState.settings.dndAutoEnabled,
                    onCheckedChange = onToggleDndAuto,
                    modifier = Modifier.testTag("dnd_auto_switch")
                )
            }

            // Shizuku Pro Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = if (uiState.shizukuStatus.permissionGranted)
                            MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "حالت حرفه‌ای Shizuku Pro",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val shizukuDesc = when {
                            uiState.shizukuStatus.binderAlive && uiState.shizukuStatus.permissionGranted ->
                                "متصل و مجاز (Game Mode + Animation 0.5x)"
                            uiState.shizukuStatus.binderAlive ->
                                "Shizuku فعال است — مجوز صادر نشده"
                            uiState.shizukuStatus.installed ->
                                "Shizuku نصب است اما سرویس خاموش است"
                            else ->
                                "حالت استاندارد (بدون نیاز به Shizuku)"
                        }
                        Text(
                            text = shizukuDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedVisibility(
                            visible = uiState.shizukuStatus.binderAlive &&
                                !uiState.shizukuStatus.permissionGranted
                        ) {
                            TextButton(
                                onClick = onRequestShizukuPermission,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.testTag("request_shizuku_button")
                            ) {
                                Text("درخواست مجوز Shizuku")
                            }
                        }
                    }
                }
                Switch(
                    checked = uiState.settings.shizukuProModeEnabled,
                    onCheckedChange = onToggleShizukuPro,
                    modifier = Modifier.testTag("shizuku_pro_switch")
                )
            }

            // ADPF Target FPS Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "نرخ فریم ADPF",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 90, 120).forEach { fps ->
                        FilterChip(
                            selected = uiState.settings.targetFps == fps,
                            onClick = { onSelectTargetFps(fps) },
                            label = {
                                Text(
                                    text = "$fps",
                                    style = TabularMetricSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("fps_chip_$fps")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionCluster(
    uiState: BoosterUiState,
    onToggleTunnel: () -> Unit,
    onEngageBoost: (Boolean) -> Unit,
    onDisengageBoost: () -> Unit,
    codmInstalled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onToggleTunnel,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .minimumInteractiveComponentSize()
                .testTag("tunnel_toggle_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isTunnelActive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                contentColor = if (uiState.isTunnelActive) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = if (uiState.isTunnelActive) Icons.Filled.PowerSettingsNew
                else Icons.Filled.Shield,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    uiState.isTunnelConnecting -> "در حال برقراری تونل..."
                    uiState.isTunnelActive -> "قطع اتصال ایمن"
                    uiState.settings.routingMode == RoutingMode.DOH_TURBO ->
                        "فعال‌سازی DoH Turbo (صفر سربار)"
                    else ->
                        "اتصال تونل مخفی AmneziaWG"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onEngageBoost(codmInstalled) },
                modifier = Modifier
                    .weight(1.3f)
                    .height(52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("engage_boost_launch_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.RocketLaunch,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "بوست و اجرای CODM",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = {
                    if (uiState.hardware.adpfSessionActive) onDisengageBoost()
                    else onEngageBoost(false)
                },
                modifier = Modifier
                    .weight(0.75f)
                    .height(52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("adpf_boost_only_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (uiState.hardware.adpfSessionActive) "توقف" else "فقط بوست",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
