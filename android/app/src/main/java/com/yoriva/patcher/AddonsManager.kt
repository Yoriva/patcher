package com.yoriva.patcher

import android.app.Application
import android.os.Build
import android.os.Environment
import com.yoriva.data.BundleProvider
import com.yoriva.jobs.JobProgress
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
    private val bundleProvider: BundleProvider,
    private val repo: String,
    private val abiProvider: () -> String,
    private val unzipInto: (File, File) -> Int,
    private val patchMetamodConfig: (File, String) -> Unit,
    private val applyGamedataAbiPolicy: (File, String) -> Boolean,
) {

    private val _addons = MutableStateFlow<AddonsState>(AddonsState.None)
    val addons: StateFlow<AddonsState> = _addons.asStateFlow()


    private val defaultGameId = com.yoriva.patcher.PatcherViewModel.GAME_CSTRIKE

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
            JobProgress.begin(app, com.yoriva.patcher.PatcherViewModel.JOB_ADDONS, "Addons", "Resolving the release…")
            try {
                val tag = com.yoriva.patcher.PatcherViewModel.ASSETS_TAG
                val zip = File(bundleProvider.cacheDir(), "amxx-addons.zip")
                _addons.value = AddonsState.Downloading(0f, "Downloading addons…")
                com.yoriva.data.ReleaseRepository.downloadUrl(
                    com.yoriva.data.ReleaseRepository.assetUrl(repo, tag, "amxx-addons.zip"),
                    zip,
                ) { done, total ->
                    _addons.value = AddonsState.Downloading(
                        if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
                        "Downloading addons…"
                    )
                    JobProgress.progress(app, com.yoriva.patcher.PatcherViewModel.JOB_ADDONS, done, total, "Downloading…")
                }
                _addons.value = AddonsState.Downloading(1f, "Extracting into ${_installPath.value.substringAfterLast("/")}…")
                val target = File(_installPath.value)
                val count = unzipInto(zip, target)
                patchMetamodConfig(target, abiProvider())
                val gamedataToggled = applyGamedataAbiPolicy(target, abiProvider())
                JobProgress.finish(app, com.yoriva.patcher.PatcherViewModel.JOB_ADDONS, "Addons installed")
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
                JobProgress.finish(app, com.yoriva.patcher.PatcherViewModel.JOB_ADDONS, t.message ?: "Install failed", ok = false)
            }
        }
    }

    fun refreshInstalled() {
        _installPath.value = gameDirFor(defaultGameId)
        _installed.value = isInstalledOnDisk()
    }

    fun reset() {
        _addons.value = AddonsState.None
        _installed.value = isInstalledOnDisk()
    }

    fun autoInstallAddons() {
        scope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return@launch
            }
            val gameDir = File(_installPath.value)
            if (!gameDir.exists()) return@launch
            if (File(gameDir, "addons/amxmodx/configs/amxx.cfg").isFile &&
                File(gameDir, "addons/metamod/config.ini").isFile
            ) {
                _installed.value = true
                applyGamedataAbiPolicy(gameDir, abiProvider())
                return@launch
            }
            fetchAndInstallAddons()
        }
    }

}
