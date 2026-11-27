package com.yoriva.patcher

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.yoriva.data.BundleProvider
import com.yoriva.data.ReleaseRepository
import com.yoriva.jobs.JobProgress
import com.yoriva.lib.ApkPatcher
import com.yoriva.lib.Bundle
import com.yoriva.lib.BundleManifest
import com.yoriva.lib.SigningKeystore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class PatchManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val bundleManager: BundleManager,
    private val bundleProvider: BundleProvider,
    private val repo: String,
    private val sourceProvider: () -> File?,
    private val installPathProvider: () -> String,
    private val abiProvider: () -> String,
    private val loadSigningKeystore: () -> SigningKeystore,
    private val workFile: (String) -> File,
    private val installAddons: () -> Unit = {},
) {

    private val patcherPrefs by lazy {
        app.getSharedPreferences("patcher_prefs", android.content.Context.MODE_PRIVATE)
    }

    private val _patchWorkers = MutableStateFlow(0)
    val patchWorkers: StateFlow<Int> = _patchWorkers.asStateFlow()

    init {
        val saved = patcherPrefs.getInt("patch_workers", 0)
        _patchWorkers.value = if (saved > 0) saved.coerceIn(1, maxPatchWorkers()) else maxPatchWorkers()
    }

    fun maxPatchWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    fun effectivePatchWorkers(): Int = _patchWorkers.value.coerceIn(1, maxPatchWorkers())
    fun setPatchWorkers(count: Int) {
        val clamped = count.coerceIn(1, maxPatchWorkers())
        if (clamped == _patchWorkers.value) return
        _patchWorkers.value = clamped
        patcherPrefs.edit().putInt("patch_workers", clamped).apply()
    }

    private val _patchLog = MutableStateFlow<List<String>>(emptyList())
    val patchLog: StateFlow<List<String>> = _patchLog.asStateFlow()

    private val _patch = MutableStateFlow<PatchUiState>(PatchUiState.Idle)
    val patch: StateFlow<PatchUiState> = _patch.asStateFlow()

    private var lastReport: ApkPatcher.PatchReport? = null
    private var installAfterPatch = false

    internal fun failPatch(message: String) {
        _patch.value = PatchUiState.Failed(message)
    }

    private val requiredGameAssets = listOf(
        "touch.cfg",
        "touch_default/numbers.cfg",
        "BotProfile.db",
        "BotChatter.db",
        "gfx/shell/splash.bmp",
        "maps/tr_1.bsp",
        "sound/radio/bot/a.wav",
    )

    private fun missingGameAssets(gameDir: File): List<String> =
        requiredGameAssets.filterNot { File(gameDir, it).isFile }

    private fun removeLegacyShellBitmaps(gameDir: File) {
        val shellDir = File(gameDir, "gfx/shell")
        shellDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.endsWith(".bmp", ignoreCase = true) &&
                (name.startsWith("btn_", ignoreCase = true) ||
                    name.startsWith("btns_", ignoreCase = true) ||
                    name.startsWith("head_", ignoreCase = true))
            ) {
                file.delete()
            }
        }
    }

    private val _gameAssetsOutdated = MutableStateFlow(false)
    val gameAssetsOutdated: StateFlow<Boolean> = _gameAssetsOutdated.asStateFlow()

    internal fun sourceApkShipsAssets(apk: File): Boolean = runCatching {
        java.util.zip.ZipFile(apk).use { zf -> zf.getEntry("assets/touch.cfg") != null }
    }.getOrDefault(false)

    private fun gameAssetsZipFile(): File = File(bundleProvider.cacheDir(), "game-assets.zip")

    private fun gameAssetsUrl(): String =
        ReleaseRepository.assetUrl(repo, PatcherViewModel.ASSETS_TAG, "game-assets.zip")

    internal fun needsGameAssetsDownload(sourceApk: File?): Boolean {
        if (sourceApk == null || sourceApkShipsAssets(sourceApk)) return false
        val zip = gameAssetsZipFile()
        return !zipIsComplete(zip) || _gameAssetsOutdated.value
    }

    internal suspend fun checkGameAssetsStatus(sourceApk: File?) {
        if (sourceApk == null || sourceApkShipsAssets(sourceApk)) {
            _gameAssetsOutdated.value = false
            return
        }
        val zip = gameAssetsZipFile()
        if (!zip.exists()) {
            _gameAssetsOutdated.value = false
            return
        }
        if (!zipIsComplete(zip)) {
            _gameAssetsOutdated.value = true
            return
        }
        val remote = ReleaseRepository.probe(gameAssetsUrl())
        _gameAssetsOutdated.value = !ReleaseRepository.isRemoteSizeMatch(zip, remote)
    }

    private fun zipIsComplete(zip: File): Boolean {
        if (!zip.isFile || zip.length() == 0L) return false
        return runCatching {
            java.util.zip.ZipFile(zip).use { zf ->
                requiredGameAssets.all { zf.getEntry(it) != null }
            }
        }.getOrDefault(false)
    }

    /**
     * Ensures `game-assets.zip` exists in cache, passes archive validation, and matches the latest
     * GitHub release byte size — downloading atomically only when missing, corrupt, or updated.
     */
    suspend fun ensureGameAssetsArchive(
        onStatus: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): File {
        val zip = gameAssetsZipFile()
        val url = gameAssetsUrl()
        val remote = ReleaseRepository.probe(url)
        if (zipIsComplete(zip) && ReleaseRepository.isRemoteSizeMatch(zip, remote)) {
            _gameAssetsOutdated.value = false
            onStatus("Game content archive is up to date")
            return zip
        }
        if (zip.exists()) zip.delete()
        onStatus("Downloading game content…")
        ReleaseRepository.downloadFile(
            url = url,
            dest = zip,
            expectedSize = remote?.size ?: 0L,
            onProgress = onProgress,
        )
        if (!zipIsComplete(zip)) {
            zip.delete()
            throw IOException("game-assets.zip came back empty or incomplete")
        }
        ReleaseRepository.writeFileStamp(zip, remote)
        _gameAssetsOutdated.value = false
        return zip
    }

    suspend fun ensureGameAssetsZip(
        gameDir: File,
        onStatus: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): File {
        val zip = ensureGameAssetsArchive(onStatus = onStatus, onProgress = onProgress)
        removeLegacyShellBitmaps(gameDir)
        if (missingGameAssets(gameDir).isEmpty()) {
            onStatus("Game content already extracted")
            return zip
        }
        onStatus("Extracting game content…")
        gameDir.mkdirs()
        unzipInto(zip, gameDir)
        val stillMissing = missingGameAssets(gameDir)
        if (stillMissing.isNotEmpty()) {
            throw IOException(
                "Game content is still incomplete after extracting: " +
                    stillMissing.joinToString(", ")
            )
        }
        return zip
    }

    fun unzipInto(zip: File, target: File): Int {
        var count = 0
        java.util.zip.ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (name.isBlank()) return@forEach
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IOException("Unsafe path in addons zip: $name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    return@forEach
                }
                if (com.yoriva.patcher.PatcherViewModel.PROTECTED_ADDON_CONFIGS.contains(name) && out.exists()) return@forEach
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    fun patchMetamodConfig(gameDir: File, abi: String) {
        val suffix = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            else -> return
        }
        val metamodDir = File(gameDir, "addons/metamod").apply { mkdirs() }
        val configFile = File(metamodDir, "config.ini")
        val gamedllLine = "gamedll dlls/libcs_android_${suffix}.so"
        if (configFile.exists()) {
            val lines = configFile.readLines().toMutableList()
            val idx = lines.indexOfFirst { it.startsWith("gamedll") }
            if (idx >= 0) lines[idx] = gamedllLine else lines.add(0, gamedllLine)
            configFile.writeText(lines.joinToString("\n"))
        } else {
            configFile.writeText("$gamedllLine\n")
        }
        val pluginsFile = File(metamodDir, "plugins.ini")
        val plugins = buildString {
            append("; Metamod plugins.ini\n")
            append("; Format: <platform> <path relative to game directory>\n")
            append("; AMX Mod X core (lives in the app native dir as libamxmodx.so)\n")
            append("linux addons/amxmodx/libamxmodx.so\n")
            append("; YaPB Counter-Strike bot\n")
            append("linux addons/yapb/bin/libyapb.so\n")
        }
        pluginsFile.writeText(plugins)
    }

    fun applyGamedataAbiPolicy(gameDir: File, abi: String): Boolean {
        val customDir = File(gameDir, "addons/amxmodx/data/gamedata/common.games/custom")
        val files = customDir.listFiles() ?: return false
        val wanted = if (abi == "arm64-v8a") "arm64" else "arm32"
        var changed = false
        for (file in files) {
            if (!file.isFile) continue
            if (file.name == com.yoriva.patcher.PatcherViewModel.LEGACY_GAMEDATA_NAME) {
                if (file.delete()) changed = true
                continue
            }
            val name = file.name.removeSuffix(com.yoriva.patcher.PatcherViewModel.GAMEDATA_DISABLED_SUFFIX)
            val match = com.yoriva.patcher.PatcherViewModel.GAMEDATA_VARIANT.matchEntire(name) ?: continue
            val variant = match.groupValues[1]
            val target = if (variant == wanted) name else name + com.yoriva.patcher.PatcherViewModel.GAMEDATA_DISABLED_SUFFIX
            if (file.name == target) continue
            if (file.renameTo(File(file.parentFile, target))) changed = true
        }
        return changed
    }

    private fun logLine(tick: ApkPatcher.Progress): String? {
        val detail = tick.detail
        val leaf = detail.substringAfterLast('/')
        return when {
            tick.kind == "add" -> "+ $leaf"
            tick.kind == "asset" -> null
            tick.kind == "keep" && (detail.startsWith("lib/") || detail.startsWith("addons/")) -> "  $leaf"
            tick.kind.isEmpty() && detail.isNotBlank() -> "→ ${tick.step.name.lowercase()} · $detail"
            else -> null
        }
    }

    private fun patchStepLabel(tick: ApkPatcher.Progress): String {
        val progress = if (tick.total > 0) {
            " ${(tick.fraction * 100).toInt()}%"
        } else {
            ""
        }
        val leaf = tick.detail.substringAfterLast('/')
        return if (leaf.isNotBlank()) {
            "${tick.step.name.lowercase()} · $leaf$progress"
        } else {
            "${tick.step.name.lowercase()}$progress"
        }
    }

    private fun stepCounters(
        tick: ApkPatcher.Progress,
        prev: PatchUiState.Running?,
    ): Map<String, String> {
        val libs = (prev?.counters?.get("libs")?.toIntOrNull() ?: 0) + if (tick.kind == "add") 1 else 0
        val entries = if (tick.total > 0) "${tick.index}/${tick.total}" else ""
        return mapOf("libs" to libs.toString(), "entries" to entries)
    }

    fun startPatch(selectedComponentKeys: Set<String>? = null) {
        if (_patch.value is PatchUiState.Running) return
        if (!EngineProbe.isEngineInstalled(app)) {
            _patch.value = PatchUiState.Failed(
                "Xash3D engine is not installed. Install the engine before patching."
            )
            return
        }
        val gameDir = File(installPathProvider())
        if (!gameDir.isDirectory) {
            _patch.value = PatchUiState.Failed(
                "The cstrike folder was not found at ${gameDir.path}. " +
                    "All game files must be installed before patching."
            )
            return
        }
        val src = sourceProvider() ?: return
        val b = bundleManager.loadedBundle ?: return
        val selAbi = abiProvider()
        val bundleAbi = b.manifest.abi.ifBlank { selAbi }
        if (bundleAbi != selAbi) {
            _patch.value = PatchUiState.Failed(
                "The loaded bundle is built for $bundleAbi but you selected $selAbi. " +
                    "Download the bundle for $selAbi first."
            )
            return
        }
        val effectiveBundle = if (selectedComponentKeys == null) {
            b
        } else {
            val kept = b.manifest.entries.filter { bundleManager.componentKeyFor(it.target) in selectedComponentKeys }
            b.withEntries(kept)
        }
        val customs = bundleManager.customLibEntries(b, selectedComponentKeys ?: emptySet())
        val finalBundle = if (customs.isEmpty()) {
            effectiveBundle
        } else {
            val customTargets = customs.map { it.first }.toSet()
            val keptEntries = effectiveBundle.manifest.entries.filterNot { it.target in customTargets }
            val addedEntries = customs.map { (target, file) ->
                BundleManifest.BundleEntry(
                    source = "custom/${file.name}",
                    target = target,
                    method = BundleManifest.Compression.STORED,
                    required = true,
                    description = "User library",
                )
            }
            val extraDisk = customs.associate { (target, file) -> "custom/${file.name}" to file }
            Bundle(
                manifest = effectiveBundle.manifest.copy(entries = keptEntries + addedEntries),
                files = effectiveBundle.files,
                diskFiles = effectiveBundle.diskFiles + extraDisk,
            )
        }
        if (finalBundle.manifest.entries.isEmpty()) {
            _patch.value = PatchUiState.Failed("No patch components selected.")
            return
        }
        val keystore = runCatching { loadSigningKeystore() }
            .getOrElse {
                _patch.value = PatchUiState.Failed("Signing key could not be loaded: ${it.message}")
                return
            }
        val out = workFile("patched.apk")
        if (out.exists()) out.delete()
        scope.launch(Dispatchers.IO) {
            JobProgress.begin(app, com.yoriva.patcher.PatcherViewModel.JOB_PATCH, "Patching", "Analyzing the source APK…")
            _patch.value = PatchUiState.Running(ApkPatcher.Step.ANALYZE, 0f)
            try {
                val gameDir = File(installPathProvider())
                val assetsZip = if (sourceApkShipsAssets(src)) {
                    JobProgress.detail(app, com.yoriva.patcher.PatcherViewModel.JOB_PATCH, "Source APK ships the game content")
                    null
                } else {
                    ensureGameAssetsZip(
                        gameDir = gameDir,
                        onStatus = { msg ->
                            JobProgress.detail(app, com.yoriva.patcher.PatcherViewModel.JOB_PATCH, msg)
                        },
                        onProgress = { done, total ->
                            JobProgress.progress(app, com.yoriva.patcher.PatcherViewModel.JOB_PATCH, done, total, "Downloading game content…")
                        },
                    )
                }
                applyGamedataAbiPolicy(gameDir, selAbi)
                val report = ApkPatcher.patch(
                    ApkPatcher.PatchRequest(
                        src, out, finalBundle, keystore,
                        keepAbi = selAbi,
                        assetsZip = assetsZip,
                        workers = effectivePatchWorkers(),
                    ),
                    onProgress = { tick ->
                        val prev = _patch.value as? PatchUiState.Running
                        JobProgress.progress(
                            app,
                            PatcherViewModel.JOB_PATCH,
                            (tick.fraction * 100f).toLong().coerceIn(0L, 99L),
                            100L,
                            patchStepLabel(tick),
                        )
                        val line = logLine(tick)
                        val log = (prev?.log.orEmpty() + listOfNotNull(line)).takeLast(200)
                        _patchLog.value = log
                        _patch.value = PatchUiState.Running(
                            step = tick.step,
                            progress = tick.fraction,
                            detail = tick.detail,
                            index = tick.index,
                            total = tick.total,
                            log = log,
                            counters = stepCounters(tick, prev),
                        )
                    },
                )
                lastReport = report
                val finished = _patchLog.value +
                    customs.map { "custom override: ${it.first}" } +
                    if (report.addedAssetFiles > 0) {
                        listOf("→ inject · ${report.addedAssetFiles} game content files written into assets/")
                    } else {
                        emptyList()
                    }
                _patch.value = PatchUiState.Done(report, finished)
                installAddons()
                JobProgress.finish(
                    app,
                    com.yoriva.patcher.PatcherViewModel.JOB_PATCH,
                    "Patched APK is ready",
                    apkPath = out.path,
                )
                requestInstall()
                if (installAfterPatch) {
                    installAfterPatch = false
                    _pendingInstall.value = installIntentFor(out)
                }
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed(t.message ?: "Unknown error")
                JobProgress.finish(app, com.yoriva.patcher.PatcherViewModel.JOB_PATCH, t.message ?: "Patch failed", ok = false)
                installAfterPatch = false
            }
        }
    }

    fun outputApk(): File? = lastReport?.let { workFile("patched.apk") }

    private val _pendingInstall = MutableStateFlow<Intent?>(null)
    val pendingInstall: StateFlow<Intent?> = _pendingInstall.asStateFlow()

    fun consumePendingInstall() {
        _pendingInstall.value = null
    }

    private val _pendingUninstall = MutableStateFlow<Intent?>(null)
    val pendingUninstall: StateFlow<Intent?> = _pendingUninstall.asStateFlow()
    private var uninstallForCleanup = false

    fun consumePendingUninstall() {
        _pendingUninstall.value = null
    }

    private fun isTargetInstalled(pkg: String): Boolean = runCatching {
        app.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun requestCleanupUninstall() {
        val target = PatcherViewModel.GAME_CLIENT_PACKAGE
        if (!isTargetInstalled(target)) return
        uninstallForCleanup = true
        _pendingUninstall.value =
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:$target"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }

    fun requestInstall() {
        val target = PatcherViewModel.GAME_CLIENT_PACKAGE
        if (isTargetInstalled(target)) {
            _pendingUninstall.value =
                Intent(Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:$target"))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            return
        }
        _pendingInstall.value = outputApk()?.let { installIntentFor(it) }
    }

    fun onUninstallFinished() {
        if (uninstallForCleanup) {
            uninstallForCleanup = false
            installAfterPatch = false
            _patch.value = PatchUiState.Idle
            return
        }
        if (isTargetInstalled(PatcherViewModel.GAME_CLIENT_PACKAGE)) return
        val out = outputApk()
        if (out != null && out.exists() && lastReport != null) {
            _pendingInstall.value = installIntentFor(out)
        } else {
            installAfterPatch = true
            startPatch()
        }
    }

    fun installIntentFor(apk: File): Intent? {
        if (!apk.exists()) return null
        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun reset() {
        _patch.value = PatchUiState.Idle
        lastReport = null
        _patchLog.value = emptyList()
    }

}
