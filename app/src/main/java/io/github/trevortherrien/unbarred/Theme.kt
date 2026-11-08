package io.github.trevortherrien.unbarred

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** "Bold type" palette: true black surfaces, one warm orange accent. */
object Ub {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF161616)
    val SurfaceHigh = Color(0xFF202020)
    val Outline = Color(0xFF2C2C2C)
    val Track = Color(0xFF242424)
    val Text = Color(0xFFF4F2EF)
    val Muted = Color(0xFFA09A93)
    val Accent = Color(0xFFFF8A3D)
    val OnAccent = Color(0xFF1A0D00)
    val AccentSoft = Color(0x1AFF8A3D)   // ~10% accent, for selected outlines
    val Error = Color(0xFFFF6B5E)
}

private val UnbarredColors = darkColorScheme(
    primary = Ub.Accent,
    onPrimary = Ub.OnAccent,
    primaryContainer = Ub.Accent,
    onPrimaryContainer = Ub.OnAccent,
    secondary = Ub.Accent,
    onSecondary = Ub.OnAccent,
    secondaryContainer = Ub.SurfaceHigh,
    onSecondaryContainer = Ub.Text,
    background = Ub.Background,
    onBackground = Ub.Text,
    surface = Ub.Background,
    onSurface = Ub.Text,
    surfaceVariant = Ub.Track,
    onSurfaceVariant = Ub.Muted,
    surfaceContainerLowest = Ub.Background,
    surfaceContainerLow = Ub.Surface,
    surfaceContainer = Ub.Surface,
    surfaceContainerHigh = Ub.SurfaceHigh,
    surfaceContainerHighest = Ub.SurfaceHigh,
    outline = Ub.Outline,
    outlineVariant = Ub.Outline,
    error = Ub.Error,
)

private val UnbarredType = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Small all-caps section label ("SOUND MODE"). */
val SectionLabelStyle = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.7.sp, color = Ub.Muted
)

/** The oversized volume number. */
val HeroNumberStyle = TextStyle(
    fontSize = 88.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3.5).sp, lineHeight = 80.sp
)

@Composable
fun UnbarredTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UnbarredColors, typography = UnbarredType) {
        // Screens draw their own background instead of a Scaffold/Surface, so set the default
        // text/icon color here — otherwise uncolored Text falls back to black on black.
        CompositionLocalProvider(LocalContentColor provides Ub.Text, content = content)
    }
}
