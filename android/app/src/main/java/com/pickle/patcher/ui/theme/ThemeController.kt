package com.pickle.patcher.ui.theme

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val themeId: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "dynamic" else NexoraPalette.id,
    val mode: ThemeMode = ThemeMode.System,
    val amoled: Boolean = false,
) {
    val palette: Palette get() = paletteFor(themeId)
}

class ThemeController(app: Application) : AndroidViewModel(app) {

    private val prefs by lazy {
        app.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    }

    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    private fun read(): ThemeChoice {
        val defaultTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "dynamic" else NexoraPalette.id
        return ThemeChoice(
            themeId = prefs.getString("theme_id", defaultTheme) ?: defaultTheme,
            mode = ThemeMode.fromKey(prefs.getString("mode", null)),
            amoled = prefs.getBoolean("amoled", false),
        )
    }

    private fun write(next: ThemeChoice) {
        _choice.value = next
        prefs.edit().apply {
            putString("theme_id", next.themeId)
            putString("mode", next.mode.key)
            putBoolean("amoled", next.amoled)
        }.apply()
    }

    fun select(themeId: String) = write(_choice.value.copy(themeId = themeId))

    fun setMode(mode: ThemeMode) = write(_choice.value.copy(mode = mode))

    fun setMode(mode: ThemeMode, amoled: Boolean) = write(_choice.value.copy(mode = mode, amoled = amoled))

    fun setAmoled(enabled: Boolean) = write(_choice.value.copy(amoled = enabled))
}
