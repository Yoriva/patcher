package com.yoriva.ui.theme
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
internal const val SCREEN_SCALE_MIN = 0.75f
internal const val SCREEN_SCALE_MAX = 1.25f
internal const val SCREEN_SCALE_STEP = 0.05f
private fun snapScreenScale(scale: Float): Float {
    val percent = (scale.coerceIn(SCREEN_SCALE_MIN, SCREEN_SCALE_MAX) * 100f).roundToInt()
    return (percent / 5f).roundToInt() * 5 / 100f
}
enum class ThemeMode(val key: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark");
    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: System
    }
}
data class ThemeChoice(
    val keyColor: Int = 0,
    val mode: ThemeMode = ThemeMode.System,
    val amoled: Boolean = false,
    val screenScale: Float = 1f,
)
class ThemeController(app: Application) : AndroidViewModel(app) {
    private val prefs by lazy {
        app.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    }
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()
    private fun read(): ThemeChoice = runCatching {
        ThemeChoice(
            keyColor = prefs.getInt("key_color", 0),
            mode = ThemeMode.fromKey(prefs.getString("mode", null)),
            amoled = prefs.getBoolean("amoled", false),
            screenScale = snapScreenScale(prefs.getFloat("screen_scale", 1f)),
        )
    }.getOrDefault(ThemeChoice())
    private fun write(next: ThemeChoice) {
        _choice.value = next
        prefs.edit().apply {
            putInt("key_color", next.keyColor)
            putString("mode", next.mode.key)
            putBoolean("amoled", next.amoled)
            putFloat("screen_scale", next.screenScale)
        }.apply()
    }
    fun setKeyColor(keyColor: Int) = write(_choice.value.copy(keyColor = keyColor))
    fun setMode(mode: ThemeMode) = write(_choice.value.copy(mode = mode))
    fun setMode(mode: ThemeMode, amoled: Boolean) = write(_choice.value.copy(mode = mode, amoled = amoled))
    fun setScreenScale(scale: Float) {
        write(_choice.value.copy(screenScale = snapScreenScale(scale)))
    }
}