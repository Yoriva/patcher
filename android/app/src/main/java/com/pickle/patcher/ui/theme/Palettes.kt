package com.pickle.patcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

data class Palette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

private val BLACK = Color(0xFF000000)
private val WHITE = Color(0xFFFFFFFF)

fun schemeFor(p: Palette, dark: Boolean): ColorScheme = if (dark) darkOf(p) else lightOf(p)

fun softenDarkScheme(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = mix(scheme.background, scheme.primary, 0.16f),
    surface = mix(scheme.surface, scheme.primary, 0.14f),
    surfaceContainerLowest = mix(scheme.surfaceContainerLowest, scheme.primary, 0.12f),
    surfaceContainerLow = mix(scheme.surfaceContainerLow, scheme.primary, 0.14f),
    surfaceContainer = mix(scheme.surfaceContainer, scheme.primary, 0.16f),
    surfaceContainerHigh = mix(scheme.surfaceContainerHigh, scheme.primary, 0.18f),
    surfaceContainerHighest = mix(scheme.surfaceContainerHighest, scheme.primary, 0.20f),
)

private fun darkOf(p: Palette): ColorScheme {
    val accent = mix(p.primary, WHITE, 0.30f)
    val secondary = mix(p.secondary, WHITE, 0.30f)
    val tertiary = mix(p.tertiary, WHITE, 0.30f)

    return darkColorScheme(
        primary = accent,
        onPrimary = mix(p.primary, BLACK, 0.82f),
        primaryContainer = mix(p.primary, BLACK, 0.60f),
        onPrimaryContainer = mix(p.primary, WHITE, 0.88f),
        secondary = secondary,
        onSecondary = mix(p.secondary, BLACK, 0.82f),
        secondaryContainer = mix(p.secondary, BLACK, 0.60f),
        onSecondaryContainer = mix(p.secondary, WHITE, 0.88f),
        tertiary = tertiary,
        onTertiary = mix(p.tertiary, BLACK, 0.82f),
        tertiaryContainer = mix(p.tertiary, BLACK, 0.60f),
        onTertiaryContainer = mix(p.tertiary, WHITE, 0.88f),
        background = mix(p.primary, BLACK, 0.80f),
        onBackground = mix(p.primary, WHITE, 0.95f),
        surface = mix(p.primary, BLACK, 0.77f),
        onSurface = mix(p.primary, WHITE, 0.95f),
        surfaceVariant = mix(p.secondary, BLACK, 0.72f),
        onSurfaceVariant = mix(p.primary, WHITE, 0.72f),
        surfaceContainerLowest = mix(p.primary, BLACK, 0.84f),
        surfaceContainerLow = mix(p.primary, BLACK, 0.79f),
        surfaceContainer = mix(p.primary, BLACK, 0.76f),
        surfaceContainerHigh = mix(p.primary, BLACK, 0.72f),
        surfaceContainerHighest = mix(p.primary, BLACK, 0.67f),
        outline = mix(p.primary, WHITE, 0.42f),
        outlineVariant = mix(p.primary, WHITE, 0.24f),
        error = AlertRed,
        onError = BLACK,
        errorContainer = mix(AlertRed, BLACK, 0.70f),
        onErrorContainer = mix(AlertRed, WHITE, 0.80f),
        inverseSurface = mix(p.primary, WHITE, 0.88f),
        inverseOnSurface = mix(p.primary, BLACK, 0.88f),
        inversePrimary = mix(p.primary, BLACK, 0.30f),
        scrim = BLACK,
    )
}

private fun lightOf(p: Palette): ColorScheme {
    val accent = mix(p.primary, BLACK, 0.18f)
    val secondary = mix(p.secondary, BLACK, 0.22f)
    val tertiary = mix(p.tertiary, BLACK, 0.28f)

    return lightColorScheme(
        primary = accent,
        onPrimary = WHITE,
        primaryContainer = mix(p.primary, WHITE, 0.80f),
        onPrimaryContainer = mix(p.primary, BLACK, 0.60f),
        secondary = secondary,
        onSecondary = WHITE,
        secondaryContainer = mix(p.secondary, WHITE, 0.80f),
        onSecondaryContainer = mix(p.secondary, BLACK, 0.60f),
        tertiary = tertiary,
        onTertiary = WHITE,
        tertiaryContainer = mix(p.tertiary, WHITE, 0.76f),
        onTertiaryContainer = mix(p.tertiary, BLACK, 0.62f),
        background = mix(p.primary, WHITE, 0.97f),
        onBackground = mix(p.primary, BLACK, 0.88f),
        surface = mix(p.primary, WHITE, 0.99f),
        onSurface = mix(p.primary, BLACK, 0.88f),
        surfaceVariant = mix(p.secondary, WHITE, 0.88f),
        onSurfaceVariant = mix(p.primary, BLACK, 0.55f),
        surfaceContainerLowest = WHITE,
        surfaceContainerLow = mix(p.primary, WHITE, 0.96f),
        surfaceContainer = mix(p.primary, WHITE, 0.93f),
        surfaceContainerHigh = mix(p.primary, WHITE, 0.90f),
        surfaceContainerHighest = mix(p.primary, WHITE, 0.86f),
        outline = mix(p.primary, BLACK, 0.42f),
        outlineVariant = mix(p.primary, BLACK, 0.18f),
        error = mix(AlertRed, BLACK, 0.25f),
        onError = WHITE,
        errorContainer = mix(AlertRed, WHITE, 0.84f),
        onErrorContainer = mix(AlertRed, BLACK, 0.62f),
        inverseSurface = mix(p.primary, BLACK, 0.85f),
        inverseOnSurface = mix(p.primary, WHITE, 0.92f),
        inversePrimary = mix(p.primary, WHITE, 0.35f),
        scrim = BLACK,
    )
}

val NexoraPalette = Palette(
    id = "nexora",
    name = "Default",
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
)

val KeyColorOptions = listOf(
    0xFF6750A4.toInt(),
    0xFFF44336.toInt(),
    0xFFE91E63.toInt(),
    0xFF9C27B0.toInt(),
    0xFF3F51B5.toInt(),
    0xFF2196F3.toInt(),
    0xFF00BCD4.toInt(),
    0xFF009688.toInt(),
    0xFF4FAF50.toInt(),
    0xFFFFEB3B.toInt(),
    0xFFFFC107.toInt(),
    0xFFFF9800.toInt(),
    0xFF795548.toInt(),
    0xFF607D8F.toInt(),
    0xFFFF9CA8.toInt(),
)

val Presets: List<Palette> = listOf(
    Palette("dynamic", "Material You", Color(0xFF6750A4), Color(0xFF625B71), Color(0xFF7D5260)),
    NexoraPalette,
)

private val byId = Presets.associateBy { it.id }

fun presetById(id: String?): Palette? = id?.let { byId[it] }

fun paletteFor(themeId: String): Palette {
    if (themeId.startsWith("color_")) {
        val hex = themeId.removePrefix("color_")
        val colorInt = hex.toLongOrNull(16)?.toInt()
        if (colorInt != null) {
            val primary = Color(colorInt)
            val secondary = mix(primary, Color(0xFFC6BFFF), 0.45f)
            val tertiary = mix(primary, Color(0xFFFFB871), 0.45f)
            return Palette(themeId, "Custom", primary, secondary, tertiary)
        }
    }
    return presetById(themeId) ?: NexoraPalette
}
