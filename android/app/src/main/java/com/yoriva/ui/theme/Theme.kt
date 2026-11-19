package com.yoriva.ui.theme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
    val baseDensity = LocalDensity.current
    val targetScale = choice.screenScale.coerceIn(SCREEN_SCALE_MIN, SCREEN_SCALE_MAX)
    var densityScale by remember { mutableFloatStateOf(targetScale) }
    val animatedScale = remember { Animatable(targetScale) }
    LaunchedEffect(targetScale) {
        densityScale = targetScale
        if (animatedScale.value == targetScale) return@LaunchedEffect
        animatedScale.animateTo(
            targetValue = targetScale,
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        )
    }
    val targetColorScheme = remember(choice.keyColor, dark, choice.amoled, context) {
        schemeForSeed(
            keyColor = choice.keyColor,
            isDark = dark,
            isAmoled = dark && choice.amoled,
            context = context,
        )
    }
    val colorScheme = targetColorScheme.animatedColors()
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = baseDensity.density * densityScale,
            fontScale = baseDensity.fontScale,
        ),
        LocalThemeController provides controller,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YorivaTypography,
            shapes = YorivaShapes,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = animatedScale.value / densityScale
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    content()
                }
            }
        }
    }
}