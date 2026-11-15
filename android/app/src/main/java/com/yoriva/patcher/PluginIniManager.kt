package com.yoriva.patcher

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ConfigFileInfo(
    val name: String,
    val file: File,
    val group: String,
    val relPath: String,
)

private data class ScanGroup(val label: String, val path: String, val recursive: Boolean = false)

private val SCAN_GROUPS = listOf(
    ScanGroup("AMX Mod X", "addons/amxmodx/configs"),
    ScanGroup("YapB", "addons/yapb/conf"),
)

private val CONFIG_EXTENSIONS = setOf("ini", "cfg", "txt")

class PluginIniManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val installPathProvider: () -> String,
) {

    private val _pluginInis = MutableStateFlow<List<ConfigFileInfo>>(emptyList())
    val pluginInis: StateFlow<List<ConfigFileInfo>> = _pluginInis.asStateFlow()

    private val _iniText = MutableStateFlow("")
    val iniText: StateFlow<String> = _iniText.asStateFlow()

    private val _iniDirty = MutableStateFlow(false)
    val iniDirty: StateFlow<Boolean> = _iniDirty.asStateFlow()

    private val _iniSavedAt = MutableStateFlow(0L)
    val iniSavedAt: StateFlow<Long> = _iniSavedAt.asStateFlow()

    private var originalText = ""

    fun openPluginIni(file: File) {
        scope.launch(Dispatchers.IO) {
            val content = try {
                if (file.exists()) file.readText() else ""
            } catch (t: Throwable) {
                "# could not read ${file.name}\n"
            }
            originalText = content
            _iniText.value = content
            _iniDirty.value = false
        }
    }

    fun editIniText(text: String) {
        _iniText.value = text
        _iniDirty.value = text != originalText
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
                originalText = text
                _iniDirty.value = false
                _iniSavedAt.value = System.currentTimeMillis()
                loadPluginInis()
            }
        }
    }

    fun revertPluginIni(file: File) = openPluginIni(file)

    fun loadPluginInis() {
        scope.launch(Dispatchers.IO) {
            val gameDir = File(installPathProvider())
            val scanned = mutableListOf<ConfigFileInfo>()
            for (group in SCAN_GROUPS) {
                val dir = if (group.path.isEmpty()) gameDir else File(gameDir, group.path)
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile) continue
                    val ext = f.extension.lowercase()
                    if (ext !in CONFIG_EXTENSIONS) continue
                    val rel = f.relativeTo(gameDir).path.replace('\\', '/')
                    scanned.add(ConfigFileInfo(f.name, f, group.label, rel))
                }
            }
            _pluginInis.value = scanned
        }
    }
}
