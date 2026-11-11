package com.pickle.patcher.patcher

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.jobs.JobProgress
import com.pickle.patcher.patcher.PatcherViewModel.AddonFileStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    private val _addonFiles = MutableStateFlow<List<AddonFileStatus>>(emptyList())
    val addonFiles: StateFlow<List<AddonFileStatus>> = _addonFiles.asStateFlow()

    private val gamePrefs by lazy {
        app.getSharedPreferences("game_prefs", Context.MODE_PRIVATE)
    }

    private fun savedGameId(): String =
        gamePrefs.getString("game_id", com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE)?.takeIf {
            it == com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE || it == com.pickle.patcher.patcher.PatcherViewModel.GAME_CZERO
        } ?: com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE

    private val gameRootCandidates = listOf(
        "/storage/emulated/0/.xash",
        "/storage/emulated/0/xash",
        "/sdcard/.xash",
        "/sdcard/xash",
    )

    private fun isGameDirInstalled(dir: File): Boolean =
        dir.isDirectory && (
            File(dir, "config.cfg").isFile ||
                File(dir, "dlls").isDirectory ||
                File(dir, "cl_dlls").isDirectory ||
                File(dir, "maps").isDirectory ||
                File(dir, "valve.rc").isFile
            )

    internal fun resolveGameRoot(): String {
        val preferred = savedGameId()
        for (root in gameRootCandidates) {
            if (isGameDirInstalled(File(root, preferred))) return root
        }
        for (root in gameRootCandidates) {
            for (game in listOf(com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE, com.pickle.patcher.patcher.PatcherViewModel.GAME_CZERO)) {
                if (isGameDirInstalled(File(root, game))) return root
            }
        }
        return "/storage/emulated/0/.xash"
    }

    private fun gameDirFor(gameId: String): String = "${resolveGameRoot()}/$gameId"

    private val _installPath = MutableStateFlow(gameDirFor(savedGameId()))
    val installPath: StateFlow<String> = _installPath.asStateFlow()
    val gameId: StateFlow<String> = _installPath
        .map { path -> if (path.endsWith("/czero")) com.pickle.patcher.patcher.PatcherViewModel.GAME_CZERO else com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE }
        .stateIn(scope, SharingStarted.Eagerly, com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE)

    internal fun installPathValue(): String = _installPath.value

    fun setGame(gameId: String) {
        if (gameId != com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE && gameId != com.pickle.patcher.patcher.PatcherViewModel.GAME_CZERO) return
        gamePrefs.edit().putString("game_id", gameId).apply()
        _installPath.value = gameDirFor(gameId)
        runAutoDetectAbi()
        scanAddonsStatus()
    }

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
                scanAddonsStatus()
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
                JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_ADDONS, t.message ?: "Install failed", ok = false)
            }
        }
    }

    fun installAddonsFromBundle() {
        fetchAndInstallAddons()
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
            scanAddonsStatus()
        }
    }

    fun scanAddonsStatus() {
        scope.launch(Dispatchers.IO) {
            val gameDir = File(_installPath.value)
            val bundle = bundleManager.loadedBundle ?: bundleProvider.loadCachedBundle(abiProvider())
            val expected = mutableSetOf<String>()
            if (bundle != null) {
                for (entry in bundle.manifest.entries) {
                    if (!entry.target.startsWith("addons/")) continue
                    expected.add(entry.target)
                }
            }
            val result = mutableListOf<AddonFileStatus>()
            for (target in expected.sorted()) {
                val file = File(gameDir, target)
                if (!file.exists()) {
                    result.add(AddonFileStatus(target, expected = true, installed = false))
                    continue
                }
                var outdated = false
                try {
                    val entry = bundle?.manifest?.entries?.firstOrNull { it.target == target }
                    val content = entry?.let { bundle?.resolveEntry(it) }
                    if (content != null) {
                        outdated = content.size.toLong() != file.length() ||
                            !content.contentEquals(file.readBytes())
                    }
                } catch (_: Throwable) {
                    outdated = false
                }
                result.add(AddonFileStatus(target, expected = true, installed = true, outdated = outdated))
            }
            _addonFiles.value = result
        }
    }
}
