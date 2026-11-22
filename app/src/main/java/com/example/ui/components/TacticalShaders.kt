package com.example.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val TACTICAL_GRID_AGSL = """
    uniform float2 uResolution;
    uniform float uTime;
    uniform float4 uAccent;
    uniform float uIntensity;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
        float2 centered = uv - float2(0.5, 0.28);
        centered.x *= uResolution.x / max(uResolution.y, 1.0);
        float dist = length(centered);

        float radialGlow = exp(-dist * 3.1) * (0.14 + 0.10 * uIntensity);

        float2 gridPx = mod(fragCoord, 48.0);
        float gridLine = step(46.5, gridPx.x) + step(46.5, gridPx.y);
        float gridAlpha = clamp(gridLine, 0.0, 1.0) * 0.045;

        float scanY = fract(uTime * 0.11);
        float scanDelta = abs(uv.y - scanY);
        float scanBand = smoothstep(0.06, 0.0, scanDelta) * (0.06 + 0.05 * uIntensity);

        float3 baseBg = float3(0.035, 0.039, 0.059);
        float3 accentRgb = uAccent.rgb;
        float3 finalRgb = baseBg + accentRgb * (radialGlow + gridAlpha + scanBand);
        return half4(finalRgb, 1.0);
    }
"""

private const val SHOCKWAVE_AGSL = """
    uniform float2 uResolution;
    uniform float uProgress;
    uniform float4 uWaveColor;

    half4 main(float2 fragCoord) {
        if (uProgress <= 0.001 || uProgress >= 0.999) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }
        float2 center = uResolution * 0.5;
        float maxRadius = min(uResolution.x, uResolution.y) * 0.55;
        float dist = length(fragCoord - center);
        float waveRadius = uProgress * maxRadius;
        float thickness = mix(28.0, 6.0, uProgress);
        float ring = exp(-pow((dist - waveRadius) / thickness, 2.0));
        float secondary = exp(-pow((dist - waveRadius * 0.72) / (thickness * 0.7), 2.0)) * 0.55;
        float fade = (1.0 - uProgress);
        float alpha = clamp((ring + secondary) * fade * 0.75, 0.0, 1.0);
        return half4(uWaveColor.rgb * alpha, alpha);
    }
"""

@Composable
fun TacticalGridBackdrop(
    accentColor: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "tactical_grid_time")
    val timeSec by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid_time_sec"
    )
    val baseColor = MaterialTheme.colorScheme.background

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(TACTICAL_GRID_AGSL) }
        val brush = remember(shader) { ShaderBrush(shader) }

        Canvas(modifier = modifier.fillMaxSize()) {
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec)
            shader.setFloatUniform(
                "uAccent",
                accentColor.red,
                accentColor.green,
                accentColor.blue,
                accentColor.alpha
            )
            shader.setFloatUniform("uIntensity", if (isActive) 1.0f else 0.35f)
            drawRect(brush = brush)
        }
    } else {
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(color = baseColor)
            val center = Offset(size.width * 0.5f, size.height * 0.28f)
            val radius = size.minDimension * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (isActive) 0.16f else 0.07f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            val stepPx = 48.dp.toPx()
            val lineColor = accentColor.copy(alpha = 0.05f)
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
                x += stepPx
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += stepPx
            }
        }
    }
}

@Composable
fun BoostShockwaveOverlay(
    triggerKey: Int,
    waveColor: Color,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(triggerKey) {
        if (triggerKey > 0) {
            progress.snapTo(0.01f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 780, easing = LinearEasing)
            )
            progress.snapTo(0f)
        }
    }

    val p = progress.value
    if (p <= 0.001f || p >= 0.999f) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(SHOCKWAVE_AGSL) }
        val brush = remember(shader) { ShaderBrush(shader) }

        Canvas(modifier = modifier.fillMaxSize()) {
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uProgress", p)
            shader.setFloatUniform(
                "uWaveColor",
                waveColor.red,
                waveColor.green,
                waveColor.blue,
                waveColor.alpha
            )
            drawRect(brush = brush)
        }
    } else {
        Canvas(modifier = modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val maxR = size.minDimension * 0.5f
            val r1 = p * maxR
            val r2 = r1 * 0.72f
            val alpha = (1f - p).coerceIn(0f, 1f)
            drawCircle(
                color = waveColor.copy(alpha = alpha * 0.55f),
                radius = r1,
                center = center,
                style = Stroke(width = (10.dp.toPx() * alpha).coerceAtLeast(1.5f))
            )
            drawCircle(
                color = waveColor.copy(alpha = alpha * 0.35f),
                radius = r2,
                center = center,
                style = Stroke(width = (5.dp.toPx() * alpha).coerceAtLeast(1f))
            )
        }
    }
}
