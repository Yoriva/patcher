package com.pickle.patcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val NexoraShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController was read outside NexoraTheme")
}

fun ColorScheme.toAmoled(): ColorScheme {
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0D0F14),
        surfaceContainer = Color(0xFF13171F),
        surfaceContainerHigh = Color(0xFF1B202B),
        surfaceContainerHighest = Color(0xFF232937),
    )
}

@Composable
fun NexoraTheme(
    choice: ThemeChoice,
    controller: ThemeController,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (choice.mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val baseScheme = if (choice.themeId == "dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) softenDarkScheme(dynamicDarkColorScheme(context)) else dynamicLightColorScheme(context)
    } else {
        schemeFor(choice.palette, dark)
    }

    val finalScheme = if (dark && choice.amoled) {
        baseScheme.toAmoled()
    } else {
        baseScheme
    }

    CompositionLocalProvider(LocalThemeController provides controller) {
        MaterialTheme(
            colorScheme = finalScheme,
            typography = NexoraTypography,
            shapes = NexoraShapes,
            content = content,
        )
    }
}
