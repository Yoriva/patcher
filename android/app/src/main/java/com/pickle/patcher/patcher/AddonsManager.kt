package com.pickle.patcher.patcher

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.jobs.JobProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class AddonsManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val bundleManager: BundleManager,
    private val bundleProvider: BundleProvider,
    private val repo: String,
    private val abiProvider: () -> String,
    private val runAutoDetectAbi: () -> Unit,
    private val unzipInto: (File, File) -> Int,
    private val patchMetamodConfig: (File, String) -> Unit,
    private val applyGamedataAbiPolicy: (File, String) -> Boolean,
) {

    private val _addons = MutableStateFlow<AddonsState>(AddonsState.None)
    val addons: StateFlow<AddonsState> = _addons.asStateFlow()


    private val defaultGameId = com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE

    private val _installed = MutableStateFlow(isInstalledOnDisk())
    val addonsInstalled: StateFlow<Boolean> = _installed.asStateFlow()
    private fun isInstalledOnDisk(): Boolean {
        val root = File(gameDirFor(defaultGameId))
        return File(root, "addons/amxmodx/configs/amxx.cfg").isFile ||
            File(root, "addons/metamod/config.ini").isFile
    }

    internal fun resolveGameRoot(): String =
        EngineProbe.resolveGameRoot(app, defaultGameId)

    private fun gameDirFor(gameId: String): String = "${resolveGameRoot()}/$gameId"

    private val _installPath = MutableStateFlow(gameDirFor(defaultGameId))
    val installPath: StateFlow<String> = _installPath.asStateFlow()
    val gameId: StateFlow<String> = MutableStateFlow(defaultGameId)

    internal fun installPathValue(): String = _installPath.value

    fun fetchAndInstallAddons() {
        scope.launch(Dispatchers.IO) {
            _addons.value = AddonsState.Downloading(0f, "Resolving latest release…")
            JobProgress.begin(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_ADDONS, "Addons", "Resolving the release…")
            try {
                val tag = "extras"
                val zip = File(bundleProvider.cacheDir(), "amxx-addons.zip")
                _addons.value = AddonsState.Downloading(0f, "Downloading addons…")
                com.pickle.patcher.data.ReleaseRepository.downloadUrl(
                    com.pickle.patcher.data.ReleaseRepository.assetUrl(repo, tag, "amxx-addons.zip"),
                    zip,
                ) { done, total ->
                    _addons.value = AddonsState.Downloading(
                        if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
                        "Downloading addons…"
                    )
                    JobProgress.progress(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_ADDONS, done, total, "Downloading…")
                }
                _addons.value = AddonsState.Downloading(1f, "Extracting into ${_installPath.value.substringAfterLast("/")}…")
                val target = File(_installPath.value)
                val count = unzipInto(zip, target)
                patchMetamodConfig(target, abiProvider())
                val gamedataToggled = applyGamedataAbiPolicy(target, abiProvider())
                JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_ADDONS, "Addons installed")
                _addons.value = AddonsState.Done(
                    buildString {
                        append("Installed $count addons files into ${target.path}")
                        if (gamedataToggled) {
                            val variant = if (abiProvider() == "arm64-v8a") "arm64" else "arm32"
                            append("\n$variant gamedata override selected for ${abiProvider()}")
                        }
                    }
                )
                _installed.value = true
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
                JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_ADDONS, t.message ?: "Install failed", ok = false)
            }
        }
    }

    fun autoInstallAddons() {
        scope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return@launch
            }
            val gameDir = File(_installPath.value)
            if (!gameDir.exists()) return@launch
            val bundle = bundleManager.loadedBundle ?: bundleProvider.loadCachedBundle(abiProvider()) ?: return@launch
            var installed = 0
            for (entry in bundle.manifest.entries) {
                if (!entry.target.startsWith("addons/")) continue
                val target = File(gameDir, entry.target)
                if (target.exists()) continue
                val content = bundle.resolveEntry(entry) ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(content)
                installed++
            }
            if (installed > 0) {
                _addons.value = AddonsState.Done("Auto-installed $installed addon files")
            }
            applyGamedataAbiPolicy(gameDir, abiProvider())
            _installed.value = isInstalledOnDisk()
        }
    }

}
