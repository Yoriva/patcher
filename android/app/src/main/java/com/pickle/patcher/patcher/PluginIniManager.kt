package com.pickle.patcher.patcher

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PluginLine(
    val text: String,
    val enabled: Boolean,
    val editable: Boolean,
    val isPlugin: Boolean,
)

data class PluginIniFile(
    val name: String,
    val file: File,
    val lines: List<PluginLine>,
)

class PluginIniManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val installPathProvider: () -> String,
) {

    private val _pluginInis = MutableStateFlow<List<PluginIniFile>>(emptyList())
    val pluginInis: StateFlow<List<PluginIniFile>> = _pluginInis.asStateFlow()

    private val _iniText = MutableStateFlow("")
    val iniText: StateFlow<String> = _iniText.asStateFlow()

    private val _iniDirty = MutableStateFlow(false)
    val iniDirty: StateFlow<Boolean> = _iniDirty.asStateFlow()

    private val _iniSavedAt = MutableStateFlow(0L)
    val iniSavedAt: StateFlow<Long> = _iniSavedAt.asStateFlow()

    fun openPluginIni(file: File) {
        scope.launch(Dispatchers.IO) {
            _iniText.value = try {
                if (file.exists()) file.readText() else ""
            } catch (t: Throwable) {
                "# could not read ${file.name}\n"
            }
            _iniDirty.value = false
        }
    }

    fun editIniText(text: String) {
        _iniText.value = text
        _iniDirty.value = true
    }

    fun savePluginIni(file: File) {
        val text = _iniText.value
        scope.launch(Dispatchers.IO) {
            val ok = try {
                file.parentFile?.mkdirs()
                file.writeText(text)
                true
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                _iniDirty.value = false
                _iniSavedAt.value = System.currentTimeMillis()
                loadPluginInis()
            }
        }
    }

    fun revertPluginIni(file: File) = openPluginIni(file)

    fun loadPluginInis() {
        scope.launch(Dispatchers.IO) {
            val configs = File(File(installPathProvider()), "addons/amxmodx/configs")
            val files = configs.listFiles { f ->
                f.isFile && f.name.startsWith("plugins") && f.name.endsWith(".ini")
            }?.sortedBy { it.name } ?: emptyList()
            _pluginInis.value = files.map { f ->
                PluginIniFile(
                    name = f.name,
                    file = f,
                    lines = f.readLines().map { line ->
                        val t = line.trim()
                        val stripped = t.removePrefix(";").trim()
                        val first = stripped.split(Regex("\\s+")).firstOrNull().orEmpty()
                        if (first.endsWith(".amxx", ignoreCase = true)) {
                            PluginLine(line, enabled = !t.startsWith(";"), editable = true, isPlugin = true)
                        } else {
                            PluginLine(line, enabled = true, editable = false, isPlugin = false)
                        }
                    }
                )
            }
        }
    }

    fun togglePluginLine(iniName: String, index: Int) {
        scope.launch(Dispatchers.IO) {
            val current = _pluginInis.value
            val ini = current.firstOrNull { it.name == iniName } ?: return@launch
            if (index !in ini.lines.indices) return@launch
            val line = ini.lines[index]
            if (!line.editable) return@launch
            val updated = ini.lines.toMutableList()
            updated[index] = if (line.enabled) {
                line.copy(text = ";" + line.text, enabled = false)
            } else {
                line.copy(text = line.text.replaceFirst(Regex("^\\s*;"), ""), enabled = true)
            }
            try {
                ini.file.writeText(updated.joinToString("\n") { it.text })
            } catch (_: Throwable) {
                return@launch
            }
            _pluginInis.value = current.map {
                if (it.name == iniName) it.copy(lines = updated) else it
            }
        }
    }
}
