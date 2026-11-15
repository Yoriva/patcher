package com.yoriva.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
val YorivaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController was read outside YorivaTheme")
}
@Composable
fun YorivaTheme(
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
    val colorScheme = schemeForSeed(
        keyColor = choice.keyColor,
        isDark = dark,
        isAmoled = dark && choice.amoled,
        context = context,
    )
    CompositionLocalProvider(LocalThemeController provides controller) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YorivaTypography,
            shapes = YorivaShapes,
            content = content,
        )
    }
}