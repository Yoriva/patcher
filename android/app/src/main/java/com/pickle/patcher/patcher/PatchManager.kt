package com.pickle.patcher.patcher

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.ReleaseRepository
import com.pickle.patcher.jobs.JobProgress
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.lib.ApkSignerTool
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.BundleManifest
import com.pickle.patcher.lib.SigningKeystore
import com.pickle.patcher.lib.ZipRaw
import com.pickle.patcher.patcher.PatcherViewModel.PatchHistoryEntry
import com.pickle.patcher.patcher.PatcherViewModel.PatchSummary
import com.pickle.patcher.patcher.PatcherViewModel.PatchCheck
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

    private val _patchHistory = MutableStateFlow<List<PatchHistoryEntry>>(emptyList())
    val patchHistory: StateFlow<List<PatchHistoryEntry>> = _patchHistory.asStateFlow()

    private val _patchCheck = MutableStateFlow<PatchCheck>(PatchCheck.Idle)
    val patchCheck: StateFlow<PatchCheck> = _patchCheck.asStateFlow()

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
        "gfx/shell/btn_touch.bmp",
        "maps/tr_1.bsp",
        "sound/radio/bot/a.wav",
    )

    private fun missingGameAssets(gameDir: File): List<String> =
        requiredGameAssets.filterNot { File(gameDir, it).isFile }

    private fun sourceApkShipsAssets(apk: File): Boolean = runCatching {
        java.util.zip.ZipFile(apk).use { zf -> zf.getEntry("assets/touch.cfg") != null }
    }.getOrDefault(false)

    private fun zipIsComplete(zip: File): Boolean {
        if (!zip.isFile || zip.length() == 0L) return false
        return runCatching {
            java.util.zip.ZipFile(zip).use { zf ->
                requiredGameAssets.all { zf.getEntry(it) != null }
            }
        }.getOrDefault(false)
    }

    suspend fun ensureGameAssetsZip(
        gameDir: File,
        onStatus: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): File? {
        val zip = File(bundleProvider.cacheDir(), "game-assets.zip")
        if (zipIsComplete(zip)) {
            onStatus("Game content archive already downloaded")
        } else {
            if (zip.exists()) zip.delete()
            onStatus("Downloading game content…")
            val tag = "extras"
            ReleaseRepository.downloadUrl(
                ReleaseRepository.assetUrl(repo, tag, "game-assets.zip"),
                zip,
                onProgress = onProgress,
            )
            if (!zipIsComplete(zip)) {
                throw IOException("game-assets.zip came back empty or incomplete")
            }
        }
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
                if (com.pickle.patcher.patcher.PatcherViewModel.PROTECTED_ADDON_CONFIGS.contains(name) && out.exists()) return@forEach
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
        val isCounterStrike = gameDir.name == com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE
        val metamodDir = File(gameDir, "addons/metamod").apply { mkdirs() }
        val configFile = File(metamodDir, "config.ini")
        val gamedllLine = "gamedll dlls/libcs_android_${suffix}.so"
        if (isCounterStrike) {
            if (configFile.exists()) {
                val lines = configFile.readLines().toMutableList()
                val idx = lines.indexOfFirst { it.startsWith("gamedll") }
                if (idx >= 0) lines[idx] = gamedllLine else lines.add(0, gamedllLine)
                configFile.writeText(lines.joinToString("\n"))
            } else {
                configFile.writeText("$gamedllLine\n")
            }
        } else {
            if (configFile.exists()) {
                configFile.writeText(
                    configFile.readLines()
                        .filterNot { it.trimStart().startsWith("gamedll") }
                        .joinToString("\n")
                )
            }
        }
        val pluginsFile = File(metamodDir, "plugins.ini")
        val plugins = if (isCounterStrike) {
            buildString {
                append("; Metamod plugins.ini\n")
                append("; Format: <platform> <path relative to game directory>\n")
                append("; AMX Mod X core (lives in the app native dir as libamxmodx.so)\n")
                append("linux addons/amxmodx/libamxmodx.so\n")
                append("; YaPB Counter-Strike bot\n")
                append("linux addons/yapb/bin/libyapb.so\n")
            }
        } else {
            buildString {
                append("; Metamod plugins.ini - ${gameDir.name}\n")
                append("; Nothing is loaded here on purpose: the AMXX stack needs a\n")
                append("; Counter-Strike game dll (ReGameDLL_CS) and YaPB is a\n")
                append("; Counter-Strike bot. Loading either under Condition Zero crashes\n")
                append("; the game, so ${gameDir.name} is left as the shipped mod.\n")
            }
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
            if (file.name == com.pickle.patcher.patcher.PatcherViewModel.LEGACY_GAMEDATA_NAME) {
                if (file.delete()) changed = true
                continue
            }
            val name = file.name.removeSuffix(com.pickle.patcher.patcher.PatcherViewModel.GAMEDATA_DISABLED_SUFFIX)
            val match = com.pickle.patcher.patcher.PatcherViewModel.GAMEDATA_VARIANT.matchEntire(name) ?: continue
            val variant = match.groupValues[1]
            val target = if (variant == wanted) name else name + com.pickle.patcher.patcher.PatcherViewModel.GAMEDATA_DISABLED_SUFFIX
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

    fun confirmReplaceAndPatch(selectedComponentKeys: Set<String>? = null) {
        val old = _replacePrompt.value
        _replacePrompt.value = null
        if (old != null && old.exists() && !old.delete()) {
            _patch.value = PatchUiState.Failed("Could not delete ${old.name}")
            return
        }
        startPatch(selectedComponentKeys)
    }

    fun dismissReplacePrompt() {
        _replacePrompt.value = null
    }

    private val _replacePrompt = MutableStateFlow<File?>(null)
    val replacePrompt: StateFlow<File?> = _replacePrompt.asStateFlow()

    fun startPatch(selectedComponentKeys: Set<String>? = null) {
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
            JobProgress.begin(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH, "Patching", "Analyzing the source APK…")
            _patch.value = PatchUiState.Running(ApkPatcher.Step.ANALYZE, 0f)
            try {
                val gameDir = File(installPathProvider())
                val assetsZip = if (sourceApkShipsAssets(src)) {
                    JobProgress.detail(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH, "Source APK ships the game content")
                    null
                } else {
                    ensureGameAssetsZip(
                        gameDir = gameDir,
                        onStatus = { msg ->
                            JobProgress.detail(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH, msg)
                        },
                        onProgress = { done, total ->
                            JobProgress.progress(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH, done, total, "Downloading game content…")
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
                        JobProgress.detail(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH, patchStepLabel(tick))
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
                persistPatchRun(selAbi, report, finished)
                JobProgress.finish(
                    app,
                    com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH,
                    "Patched APK is ready",
                    apkPath = out.path,
                )
                if (installAfterPatch) {
                    installAfterPatch = false
                    _pendingInstall.value = installIntentFor(out)
                }
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed(t.message ?: "Unknown error")
                writePatchLog(selAbi, null, _patchLog.value, t.message ?: "Unknown error")
                JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_PATCH, t.message ?: "Patch failed", ok = false)
                installAfterPatch = false
            }
        }
    }

    fun outputApk(): File? = lastReport?.let { workFile("patched.apk") }

    fun installedClientPackages(): List<String> {
        val pm = app.packageManager
        return runCatching {
            pm.getInstalledPackages(0)
                .map { it.packageName }
                .filter { it != app.packageName && isClientPackage(it) }
                .sorted()
        }.getOrElse { emptyList() }
    }

    private fun isClientPackage(name: String): Boolean =
        name.contains("cs16client", ignoreCase = true) || name.contains("xash", ignoreCase = true)

    private val _uninstallPrompt = MutableStateFlow<List<String>>(emptyList())
    val uninstallPrompt: StateFlow<List<String>> = _uninstallPrompt.asStateFlow()

    fun dismissUninstallPrompt() {
        _uninstallPrompt.value = emptyList()
    }

    private val _pendingInstall = MutableStateFlow<Intent?>(null)
    val pendingInstall: StateFlow<Intent?> = _pendingInstall.asStateFlow()

    fun consumePendingInstall() {
        _pendingInstall.value = null
    }

    private val _pendingUninstall = MutableStateFlow<Intent?>(null)
    val pendingUninstall: StateFlow<Intent?> = _pendingUninstall.asStateFlow()

    fun consumePendingUninstall() {
        _pendingUninstall.value = null
    }

    private fun isTargetInstalled(pkg: String): Boolean = runCatching {
        app.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun requestInstall() {
        val target = "su.xash.cs16client.nexora"
        if (isTargetInstalled(target)) {
            _pendingUninstall.value =
                Intent(Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:$target"))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            return
        }
        _pendingInstall.value = installIntent()
    }

    fun onUninstallFinished() {
        if (isTargetInstalled("su.xash.cs16client.nexora")) return
        val out = outputApk()
        if (out != null && out.exists() && lastReport != null) {
            _pendingInstall.value = installIntentFor(out)
        } else {
            installAfterPatch = true
            startPatch()
        }
    }

    fun confirmUninstallAndPatch() {
        val packages = _uninstallPrompt.value
        _uninstallPrompt.value = emptyList()
        for (pkg in packages) {
            runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val out = outputApk()
        if (out != null && out.exists() && lastReport != null) {
            _pendingInstall.value = installIntentFor(out)
            return
        }
        installAfterPatch = true
        startPatch()
    }

    fun installIntent(): Intent? {
        val out = outputApk() ?: return null
        return installIntentFor(out)
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
        _patchCheck.value = PatchCheck.Idle
    }

    private fun persistPatchRun(abi: String, report: ApkPatcher.PatchReport, log: List<String>) {
        val entry = PatchHistoryEntry(
            time = System.currentTimeMillis(),
            abi = abi,
            source = report.sourceName,
            entries = report.addedEntries.size,
            libs = report.moduleLibs.size,
            verified = report.verification?.verified == true,
            ok = report.success,
        )
        val history = (listOf(entry) + _patchHistory.value).take(20)
        _patchHistory.value = history
        try {
            val file = File(installPathProvider(), "patch-history.json")
            file.writeText(history.joinToString(",", prefix = "[", postfix = "]") { h ->
                "\"{\"time\":${h.time},\"abi\":\"${h.abi}\",\"source\":\"${h.source}\",\"entries\":${h.entries},\"libs\":${h.libs},\"verified\":${h.verified},\"ok\":${h.ok}}"
            })
        } catch (_: Throwable) {
        }
        writePatchLog(abi, report, log, null)
    }

    private fun writePatchLog(
        abi: String,
        report: ApkPatcher.PatchReport?,
        log: List<String>,
        error: String?,
    ) {
        try {
            val dir = File(installPathProvider())
            if (!dir.exists()) return
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val head = buildString {
                append("---- patch ").append(stamp).append(" abi=").append(abi).append(" ----\n")
                if (report != null) {
                    append("source=").append(report.sourceName)
                        .append(" entries=").append(report.addedEntries.size)
                        .append(" libs=").append(report.moduleLibs.size)
                        .append(" verified=").append(report.verification?.verified).append('\n')
                }
                if (error != null) append("error=").append(error).append('\n')
            }
            File(dir, "patch.log").appendText(head + log.joinToString("\n") + "\n")
        } catch (_: Throwable) {
        }
    }

    fun patchSummary(): PatchSummary? {
        val b = bundleManager.loadedBundle ?: return null
        val src = sourceProvider() ?: return null
        val abi = abiProvider()
        val payload = b.files.entries
            .filter { it.key.contains(abi) || it.key.startsWith("addons/") }
            .sumOf { it.value.size.toLong() }
        return PatchSummary(
            abi = abi,
            source = src.name,
            sourceSize = src.length(),
            components = b.manifest.entries.groupBy { bundleManager.componentKeyFor(it.target) }.size,
            entries = b.manifest.entries.size,
            payloadSize = payload,
        )
    }

    fun verifyPatchedApk() {
        val out = outputApk() ?: return
        if (!out.exists()) {
            _patchCheck.value = PatchCheck.Failed("patched.apk is missing")
            return
        }
        _patchCheck.value = PatchCheck.Running
        scope.launch(Dispatchers.IO) {
            try {
                val abi = abiProvider()
                val v = ApkSignerTool.verify(out)
                val zip = ZipRaw.open(out)
                val prefix = "lib/$abi/"
                val present = zip?.entries?.keys?.count { it.startsWith(prefix) && it.endsWith(".so") } ?: 0
                zip?.close()
                val expected = bundleManager.loadedBundle?.manifest?.entries?.count { it.target.startsWith(prefix) } ?: present
                val engine = File(installPathProvider(), "engine.log")
                val commit = if (engine.exists()) {
                    engine.useLines { seq ->
                        seq.firstOrNull { it.contains("Commit hash") }?.trim()?.removePrefix("Commit hash  :")?.trim().orEmpty()
                    }
                } else ""
                _patchCheck.value = PatchCheck.Result(
                    signatureOk = v.verified,
                    usedV1 = v.usedV1,
                    usedV2 = v.usedV2,
                    expectedLibs = expected,
                    presentLibs = present,
                    engineCommit = commit,
                )
            } catch (t: Throwable) {
                _patchCheck.value = PatchCheck.Failed(t.message ?: "verification failed")
            }
        }
    }
}
