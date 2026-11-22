package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val CodmDarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = ObsidianMatte,
    primaryContainer = CyberCyanContainer,
    onPrimaryContainer = CyberCyan,
    secondary = TacticalGold,
    onSecondary = ObsidianMatte,
    secondaryContainer = TacticalGoldContainer,
    onSecondaryContainer = TacticalGold,
    tertiary = EmeraldShield,
    onTertiary = ObsidianMatte,
    tertiaryContainer = EmeraldShieldContainer,
    onTertiaryContainer = EmeraldShield,
    error = AlertCrimson,
    onError = TextPrimary,
    errorContainer = AlertCrimsonContainer,
    onErrorContainer = AlertCrimson,
    background = ObsidianMatte,
    onBackground = TextPrimary,
    surface = GunmetalSurface,
    onSurface = TextPrimary,
    surfaceVariant = GunmetalElevated,
    onSurfaceVariant = TextSecondary,
    outline = TacticalBorder,
    outlineVariant = TacticalBorderSubtle
)

val TacticalShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun CodmStealthTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CodmDarkColorScheme,
        typography = CodmTypography,
        shapes = TacticalShapes,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    CodmStealthTheme(content = content)
}
