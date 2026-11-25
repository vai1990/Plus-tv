package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val BroadcastDarkColorScheme = darkColorScheme(
    primary = PulseCyan,
    onPrimary = Color(0xFF002028),
    primaryContainer = PulseCyanContainer,
    onPrimaryContainer = PulseCyan,
    secondary = LiveCrimson,
    onSecondary = Color.White,
    secondaryContainer = LiveCrimsonContainer,
    onSecondaryContainer = Color(0xFFFFB3C0),
    tertiary = DrmAmber,
    onTertiary = Color(0xFF2B1800),
    tertiaryContainer = DrmAmberContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SurfaceLow,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceElevated,
    surfaceContainerHigh = SurfaceHigh,
    outline = BorderSubtle,
    outlineVariant = Color(0xFF1F2A3C),
    error = LiveCrimson,
    onError = Color.White
)

private val StreamPulseShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StreamPulseTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BroadcastDarkColorScheme,
        typography = Typography,
        shapes = StreamPulseShapes,
        content = content
    )
}
