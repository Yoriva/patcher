package com.yoriva.ui.theme
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
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
) {
    val themeId: String get() = if (keyColor == 0) "dynamic" else "custom_$keyColor"
}
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
            screenScale = prefs.getFloat("screen_scale", 1f).coerceIn(0.25f, 2f),
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
        val snapped = ((scale.coerceIn(0.25f, 2f) * 4f).roundToInt() / 4f)
        write(_choice.value.copy(screenScale = snapped))
    }
}