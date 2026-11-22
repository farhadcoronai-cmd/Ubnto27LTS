package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bridge.DohEngineStats
import com.example.bridge.RoutingMode
import com.example.bridge.TunnelState
import com.example.bridge.TunnelStats
import com.example.ui.theme.TabularMetricLarge
import com.example.ui.theme.TabularMetricMedium
import com.example.ui.theme.TabularMetricSmall
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TacticalRadarSweep(
    isActive: Boolean,
    isConnecting: Boolean,
    isError: Boolean,
    statusLabel: String,
    subLabel: String,
    shockwaveKey: Int,
    modifier: Modifier = Modifier
) {
    val activeColor = when {
        isError -> MaterialTheme.colorScheme.error
        isConnecting -> MaterialTheme.colorScheme.secondary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val infinite = rememberInfiniteTransition(label = "radar_sweep")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActive || isConnecting) 2200 else 6500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_angle"
    )

    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_pulse"
    )

    val outlineColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier.size(210.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val maxR = size.minDimension * 0.46f

            // Concentric tactical rings
            listOf(1.0f, 0.72f, 0.44f).forEachIndexed { idx, factor ->
                drawCircle(
                    color = if (idx == 0) activeColor.copy(alpha = 0.45f) else outlineColor.copy(alpha = 0.55f),
                    radius = maxR * factor,
                    center = center,
                    style = Stroke(width = if (idx == 0) 2.dp.toPx() else 1.dp.toPx())
                )
            }

            // Crosshair lines
            drawLine(
                color = outlineColor.copy(alpha = 0.5f),
                start = Offset(center.x - maxR, center.y),
                end = Offset(center.x + maxR, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.5f),
                start = Offset(center.x, center.y - maxR),
                end = Offset(center.x, center.y + maxR),
                strokeWidth = 1.dp.toPx()
            )

            // Rotating radar sweep sector
            rotate(degrees = angle, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        0.96f to activeColor.copy(alpha = if (isActive || isConnecting) 0.34f else 0.12f),
                        1.0f to activeColor.copy(alpha = if (isActive || isConnecting) 0.75f else 0.22f),
                        center = center
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = true,
                    topLeft = Offset(center.x - maxR, center.y - maxR),
                    size = Size(maxR * 2f, maxR * 2f)
                )
            }

            // Inner hexagon core
            val hexR = maxR * 0.38f
            val hexPath = Path().apply {
                for (i in 0..5) {
                    val rad = (i * 60.0 - 30.0) * (PI / 180.0)
                    val px = center.x + (hexR * cos(rad)).toFloat()
                    val py = center.y + (hexR * sin(rad)).toFloat()
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(
                path = hexPath,
                color = activeColor.copy(alpha = if (isActive) pulseAlpha else 0.35f),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        BoostShockwaveOverlay(
            triggerKey = shockwaveKey,
            waveColor = activeColor,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = if (isActive) Icons.Filled.Shield else Icons.Filled.Bolt,
                contentDescription = statusLabel,
                tint = activeColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subLabel,
                style = TabularMetricSmall,
                color = activeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SpringMetricGauge(
    title: String,
    valueText: String,
    unit: String,
    progress: Float,
    accentColor: Color,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "gauge_spring"
    )
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 6.dp.toPx()
                    val diameter = size.minDimension - strokePx
                    val topLeft = Offset(strokePx * 0.5f, strokePx * 0.5f)
                    val arcSize = Size(diameter, diameter)

                    drawArc(
                        color = trackColor,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = accentColor,
                        startAngle = 135f,
                        sweepAngle = 270f * animatedProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = valueText,
                        style = TabularMetricMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (unit.isNotEmpty()) {
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun NetworkTelemetryStrip(
    routingMode: RoutingMode,
    tunnelStats: TunnelStats,
    dohStats: DohEngineStats,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        AnimatedContent(
            targetState = routingMode,
            label = "telemetry_mode_strip"
        ) { mode ->
            when (mode) {
                RoutingMode.DOH_TURBO -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Dns,
                                    contentDescription = "موتور DoH",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تله‌متری موتور Rust DoH",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "${dohStats.cacheHitRatePercent}% CACHE",
                                style = TabularMetricSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { (dohStats.cacheHitRatePercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricColumn("کوئری کل", dohStats.totalQueries.toString())
                            MetricColumn("کش (0ms)", dohStats.cacheHits.toString(), MaterialTheme.colorScheme.tertiary)
                            MetricColumn("ذخیره کش", "${dohStats.cacheEntries}/512")
                            MetricColumn(
                                "میانگین RTT",
                                if (dohStats.avgRttMs > 0.0) "${dohStats.avgRttMs.toInt()} ms" else "--",
                                MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                RoutingMode.AMNEZIA_WG -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Speed,
                                    contentDescription = "ترافیک AmneziaWG",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تله‌متری تونل AmneziaWG",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = when (tunnelStats.state) {
                                    TunnelState.CONNECTED -> if (tunnelStats.isStale) "STALE" else "LIVE"
                                    TunnelState.CONNECTING -> "SYNC"
                                    TunnelState.ERROR -> "ERR"
                                    TunnelState.DISCONNECTED -> "IDLE"
                                },
                                style = TabularMetricSmall,
                                color = if (tunnelStats.state == TunnelState.CONNECTED)
                                    MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricColumn(
                                "دانلود RX",
                                "${tunnelStats.rxSpeedKbps.toInt()} Kbps",
                                MaterialTheme.colorScheme.primary
                            )
                            MetricColumn(
                                "آپلود TX",
                                "${tunnelStats.txSpeedKbps.toInt()} Kbps",
                                MaterialTheme.colorScheme.secondary
                            )
                            MetricColumn("کل RX", formatBytesCompact(tunnelStats.rxBytes))
                            MetricColumn("کل TX", formatBytesCompact(tunnelStats.txBytes))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = TabularMetricSmall,
            color = valueColor,
            maxLines = 1
        )
    }
}

@Composable
fun RttHistorySparkline(
    samplesMs: List<Double>,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.height(42.dp)) {
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = 1.dp.toPx()
        )
        if (samplesMs.size < 2) return@Canvas
        val maxVal = (samplesMs.maxOrNull() ?: 50.0).coerceAtLeast(25.0)
        val stepX = size.width / (samplesMs.size - 1).toFloat()
        val path = Path()
        samplesMs.forEachIndexed { index, value ->
            val x = index * stepX
            val norm = (value / maxVal).toFloat().coerceIn(0f, 1f)
            val y = size.height - (norm * size.height * 0.85f) - size.height * 0.08f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = accentColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun HeroMetricPill(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = TabularMetricLarge.copy(fontSize = 18.sp),
                color = accentColor,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun formatBytesCompact(bytes: Long): String {
    if (bytes < 1024L) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.toInt()}KB"
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1fMB", mb)
}
