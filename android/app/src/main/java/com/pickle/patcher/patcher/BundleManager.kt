package com.pickle.patcher.patcher

import android.app.Application
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.IncrementalUpdateManager
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.BundleManifest
import com.pickle.patcher.patcher.LibInfo
import com.pickle.patcher.patcher.PatcherViewModel.CustomLib
import com.pickle.patcher.patcher.PatcherViewModel.PatchComponent
import com.pickle.patcher.patcher.BundleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class BundleManager(
    private val app: Application,
    private val bundleProvider: BundleProvider,
    private val scope: CoroutineScope,
    private val abiProvider: () -> String,
    private val appliedBuilds: (String) -> Map<String, String>,
    private val recordAppliedBuilds: (String, List<IncrementalUpdateManager.AssetInfo>) -> Unit,
    private val markBundleTagKnown: (String) -> Unit,
    private val refreshAbiStatus: () -> Unit,
    private val setReleaseNote: (String) -> Unit,
    private val externalRoot: File,
) {

    val libsDir = File(externalRoot, "libs")

    var loadedBundle: Bundle? = null
    var downloadingAbi: String? = null

    private val _bundle = MutableStateFlow<BundleState>(BundleState.None)
    val bundle: StateFlow<BundleState> = _bundle.asStateFlow()

    private val _bundleVersion = MutableStateFlow("")
    val bundleVersion: StateFlow<String> = _bundleVersion.asStateFlow()

    private val _libs = MutableStateFlow<List<LibInfo>>(emptyList())
    val libs: StateFlow<List<LibInfo>> = _libs.asStateFlow()

    private val _customLibs = MutableStateFlow<List<CustomLib>>(emptyList())
    val customLibs: StateFlow<List<CustomLib>> = _customLibs.asStateFlow()

    private val _libsChecking = MutableStateFlow(false)
    val libsChecking: StateFlow<Boolean> = _libsChecking.asStateFlow()

    private val _customLibProblem = MutableStateFlow<String?>(null)
    val customLibProblem: StateFlow<String?> = _customLibProblem.asStateFlow()

    val hasCachedBundle: Boolean get() = bundleProvider.hasCachedBundle(abiProvider())

    internal fun clearLoadedBundle() {
        loadedBundle = null
        _bundle.value = BundleState.None
    }

    internal fun setBundleVersion(version: String) {
        _bundleVersion.value = version
    }

    fun useCachedBundle() {
        val abi = abiProvider()
        val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi)
        if (files.isNotEmpty()) {
            val b = buildBundleFromFileMap(files, abi)
            loadedBundle = b
            _bundle.value = BundleState.Loaded
            _bundleVersion.value = b.manifest.version
            scanLibs()
            refreshAbiStatus()
            return
        }
        val b = bundleProvider.loadCachedBundle(abi)
        if (b != null) {
            loadedBundle = b
            _bundle.value = BundleState.Ready("Cached", b.manifest.entries.size, b.manifest.version)
            scanLibs()
        }
    }

    fun useLoadedBundle() {
        val b = loadedBundle ?: return
        _bundle.value = BundleState.Ready("Loaded", b.manifest.entries.size, b.manifest.version)
    }

    fun scanLibs(autoLoad: Boolean = false, checkRemote: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val abi = abiProvider()
            val targetDir = File(libsDir, abi)
            val localFiles = if (targetDir.isDirectory) {
                targetDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.map { it.name to it }
                    ?.toMap()
                    ?: emptyMap()
            } else emptyMap()
            _libs.value = localFiles.keys.sorted().map { name ->
                val f = localFiles[name] ?: return@map null
                LibInfo(
                    name = name,
                    localSize = f.length(),
                    releaseSize = f.length(),
                    upToDate = true,
                )
            }.filterNotNull()
            var tagName = "live"
            var assets: List<IncrementalUpdateManager.AssetInfo> = emptyList()
            if (checkRemote) {
                _libsChecking.value = true
                try {
                    tagName = "libs"
                    assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, abi)
                } catch (_: Throwable) { } finally {
                    _libsChecking.value = false
                }
            }
            if (assets.isNotEmpty()) {
                val releaseMap = assets.associateBy { it.cleanName }
                val allNames = (localFiles.keys + releaseMap.keys).distinct().sorted()
                val rows = allNames.map { name ->
                    val f = localFiles[name]
                    val asset = releaseMap[name]
                    LibInfo(
                        name = name,
                        localSize = f?.length() ?: 0L,
                        releaseSize = asset?.size ?: (f?.length() ?: 0L),
                        upToDate = if (asset != null) {
                            IncrementalUpdateManager.isUpToDate(
                                File(targetDir, name), asset, appliedBuilds(abi)[name]
                            )
                        } else true,
                    )
                }
                _libs.value = rows
            }
            val outdated = _libs.value.filter { !it.upToDate && it.releaseSize > 0 }
            if (autoLoad && outdated.isEmpty() && assets.isNotEmpty()) {
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi)
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, abi)
                    markBundleTagKnown(tagName)
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                }
            }
        }
    }

    fun refreshLibStatus() {
        scanLibs(checkRemote = true)
    }

    fun refreshSingleLib(libName: String) {
        scope.launch(Dispatchers.IO) {
            downloadLib(libName)
        }
    }

    private suspend fun downloadLib(libName: String) {
        try {
            val tagName = "libs"
            val assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, abiProvider())
            val asset = assets.find { it.cleanName == libName } ?: return
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = true, downloadProgress = 0f) else it
            }
            IncrementalUpdateManager.downloadSingle(asset, libsDir, abiProvider()) { p ->
                _libs.value = _libs.value.map {
                    if (it.name == libName) it.copy(downloading = true, downloadProgress = p) else it
                }
            }
            val fileOnDisk = File(File(libsDir, abiProvider()), libName)
            val newSize = if (fileOnDisk.exists()) fileOnDisk.length() else 0L
            val upToDate = IncrementalUpdateManager.isUpToDate(
                fileOnDisk, asset, appliedBuilds(abiProvider())[libName]
            )
            recordAppliedBuilds(abiProvider(), listOf(asset))
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(localSize = newSize, upToDate = upToDate) else it
            }
        } catch (t: Throwable) {
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = false) else it
            }
        }
    }

    fun fetchAndDownloadBundle() {
        if (_bundle.value is BundleState.Downloading) return
        downloadingAbi = abiProvider()
        refreshAbiStatus()
        scope.launch(Dispatchers.IO) {
            _bundle.value = BundleState.Downloading(0.04f)
            var jobStarted = false
            try {
                val tagName = "libs"
                setReleaseNote(tagName)
                val assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, abiProvider())
                if (assets.isEmpty()) throw IOException("No .so assets found for ABI ${abiProvider()}")
                _bundle.update { BundleState.Downloading(0.1f, tagName) }
                val diff = IncrementalUpdateManager.diff(
                    assets, libsDir, abiProvider(), appliedBuilds(abiProvider())
                )
                if (diff.toDownload.isEmpty()) {
                    val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abiProvider())
                    if (files.isEmpty()) throw IOException("No .so files found in libs/")
                    val b = buildBundleFromFileMap(files, abiProvider())
                    markBundleTagKnown(tagName)
                    recordAppliedBuilds(abiProvider(), assets)
                    _bundle.value = BundleState.Loaded
                    loadedBundle = b
                    scanLibs()
                    return@launch
                }
                com.pickle.patcher.jobs.JobProgress.begin(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_LIBS, "Libraries", "Downloading ${diff.toDownload.size} files")
                jobStarted = true
                IncrementalUpdateManager.downloadChanged(
                    diff.toDownload, libsDir, abiProvider(),
                    onFileStart = { index, asset ->
                        markLibDownloading(asset.cleanName, 0f)
                        com.pickle.patcher.jobs.JobProgress.detail(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_LIBS, asset.cleanName)
                        _bundle.update {
                            BundleState.Downloading(
                                percent = 0.15f + (0.75f * index.toFloat() / diff.toDownload.size).coerceAtMost(0.75f),
                                tagName = tagName,
                                currentFile = asset.cleanName,
                                fileIndex = index,
                                fileTotal = diff.toDownload.size,
                            )
                        }
                    },
                    onFileProgress = { index, asset, fileProgress ->
                        markLibDownloading(asset.cleanName, fileProgress)
                        val overall =
                            (index + fileProgress.coerceIn(0f, 1f)) / diff.toDownload.size.toFloat()
                        com.pickle.patcher.jobs.JobProgress.progress(
                            app,
                            com.pickle.patcher.patcher.PatcherViewModel.JOB_LIBS,
                            (overall * 100).toLong().toInt().toLong().coerceIn(0, 99),
                            100L,
                            "${asset.cleanName} ${(fileProgress * 100).toInt().coerceIn(0, 100)}%",
                        )
                        val base = 0.15f + (0.75f * index.toFloat() / diff.toDownload.size).coerceAtMost(0.75f)
                        val perFileWeight = 0.75f / diff.toDownload.size
                        _bundle.update {
                            BundleState.Downloading(
                                percent = base + perFileWeight * fileProgress,
                                tagName = tagName,
                                currentFile = asset.cleanName,
                                fileIndex = index,
                                fileTotal = diff.toDownload.size,
                            )
                        }
                    },
                )
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abiProvider())
                if (files.isEmpty()) throw IOException("No .so files found after download")
                val b = buildBundleFromFileMap(files, abiProvider())
                markBundleTagKnown(tagName)
                recordAppliedBuilds(abiProvider(), assets)
                _bundle.value = BundleState.Ready(
                    "Updated ${diff.toDownload.size} files", b.manifest.entries.size, b.manifest.version
                )
                loadedBundle = b
                downloadingAbi = null
                _bundleVersion.value = b.manifest.version
                scanLibs()
                refreshAbiStatus()
                com.pickle.patcher.jobs.JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_LIBS, "Libraries ready")
            } catch (t: Throwable) {
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abiProvider())
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, abiProvider())
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                    scanLibs()
                    if (jobStarted) {
                        com.pickle.patcher.jobs.JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_LIBS, "Using the libraries already on disk")
                    }
                } else {
                    _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
                    if (jobStarted) {
                        com.pickle.patcher.jobs.JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_LIBS, t.message ?: "Download failed", ok = false)
                    }
                }
                downloadingAbi = null
                refreshAbiStatus()
            }
        }
    }

    private fun markLibDownloading(name: String, progress: Float) {
        _libs.value = _libs.value.map {
            if (it.name == name) it.copy(downloading = true, downloadProgress = progress) else it
        }
    }

    private fun buildBundleFromFileMap(files: Map<String, File>, abi: String): Bundle {
        val suffix = if (abi == "arm64-v8a") "arm64" else "armv7l"
        val entries = files.keys.map { targetPath ->
            val name = targetPath.substringAfterLast("/")
            val desc = when {
                name == "libamxmodx.so" -> "AMX Mod X core"
                name == "libmetamod.so" || name.startsWith("libmetamod_android_") -> "Metamod HL1"
                name == "libyapb.so" -> "YaPB bot plugin"
                name == "libclient_android_$suffix.so" -> "CS16Client client DLL"
                name == "libmenu_android_$suffix.so" -> "CS16Client main menu"
                name == "libcs_android_arm64.so" -> "ReGameDLL game DLL (first-spawn fix)"
                name.contains("_amxx_") -> "AMXX module"
                else -> "Bundle file"
            }
            BundleManifest.BundleEntry(
                source = targetPath,
                target = targetPath,
                method = BundleManifest.Compression.STORED,
                required = true,
                description = desc,
            )
        }
        val manifest = BundleManifest(
            version = "live",
            game = "cs16client",
            abi = abi,
            entries = entries,
        )
        return Bundle(manifest, emptyMap(), files)
    }

    private fun customLibDir(): File =
        File(app.filesDir, "custom-libs").apply { mkdirs() }

    private fun shadowedBy(target: String): String? {
        val b = loadedBundle
        if (b != null) {
            val key = b.manifest.entries.firstOrNull { it.target == target }?.let { componentKeyFor(it.target) }
            return key?.let { labelForComponent(it) }
        }
        val key = componentKeyFor(target)
        return if (key == "other") null else labelForComponent(key)
    }

    private fun displayName(uri: android.net.Uri): String? {
        if (uri.scheme == "content") {
            try {
                app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val v = c.getString(idx)
                            if (!v.isNullOrBlank()) return v
                        }
                    }
                }
            } catch (_: Throwable) { }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    fun addCustomLibs(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val abi = abiProvider()
        val existing = _customLibs.value.map { it.name }.toMutableSet()
        val rejected = ArrayList<String>()
        val added = ArrayList<CustomLib>()
        for (uri in uris) {
            val name = displayName(uri)
            if (name.isNullOrBlank() || !name.endsWith(".so")) {
                rejected += "$name: not a .so file"
                continue
            }
            if (!existing.add(name)) {
                rejected += "$name: already added"
                continue
            }
            val target = File(customLibDir(), name)
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    rejected += "$name: could not be opened"
                    continue
                }
            } catch (t: Throwable) {
                target.delete()
                rejected += "$name: ${t.message ?: "copy failed"}"
                continue
            }
            when (val verdict = com.pickle.patcher.lib.validateElf(target, abi)) {
                is com.pickle.patcher.lib.ElfVerdict.Rejected -> {
                    target.delete()
                    existing.remove(name)
                    rejected += "$name: ${verdict.reason}"
                }
                is com.pickle.patcher.lib.ElfVerdict.Ok -> {
                    val machine = com.pickle.patcher.lib.readElfInfo(target)?.machineName ?: "?"
                    added += CustomLib(
                        name = name,
                        file = target,
                        machineName = machine,
                        shadows = shadowedBy("lib/$abi/$name"),
                    )
                }
            }
        }
        if (added.isNotEmpty()) _customLibs.value = _customLibs.value + added
        if (rejected.isNotEmpty()) {
            _customLibProblem.value = if (added.isEmpty()) {
                "Nothing was added:\n" + rejected.joinToString("\n")
            } else {
                "Added ${added.size}, refused ${rejected.size}:\n" + rejected.joinToString("\n")
            }
        }
    }

    fun dismissCustomLibProblem() {
        _customLibProblem.value = null
    }

    fun removeCustomLib(name: String) {
        _customLibs.value = _customLibs.value.filterNot {
            if (it.name == name) {
                it.file.delete()
                true
            } else {
                false
            }
        }
    }

    fun scanCustomLibs() {
        val abi = abiProvider()
        val dir = customLibDir()
        if (!dir.isDirectory) return
        val libs = dir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: return
        val loaded = libs.mapNotNull { file ->
            when (val verdict = com.pickle.patcher.lib.validateElf(file, abi)) {
                is com.pickle.patcher.lib.ElfVerdict.Ok -> CustomLib(
                    name = file.name,
                    file = file,
                    machineName = com.pickle.patcher.lib.readElfInfo(file)?.machineName ?: "?",
                    shadows = shadowedBy("lib/$abi/${file.name}"),
                )
                else -> null
            }
        }
        _customLibs.value = loaded
    }

    fun customLibEntries(bundle: Bundle, selected: Set<String>): List<Pair<String, File>> =
        _customLibs.value
            .map { "lib/${abiProvider()}/${it.name}" to it.file }

    private fun labelForComponent(key: String): String = when (key) {
        "amxx" -> "AMX Mod X core"
        "metamod" -> "Metamod HL1"
        "yapb" -> "YaPB bot plugin"
        "client" -> "CS16Client client DLL"
        "menu" -> "CS16Client main menu"
        "game" -> "ReGameDLL game DLL"
        "modules" -> "AMXX modules"
        else -> "Bundle files"
    }

    fun staleCustomLibs(): List<CustomLib> {
        val abi = abiProvider()
        return _customLibs.value.filter {
            val verdict = com.pickle.patcher.lib.validateElf(it.file, abi)
            verdict !is com.pickle.patcher.lib.ElfVerdict.Ok
        }
    }

    internal fun componentKeyFor(target: String): String {
        val name = target.substringAfterLast("/")
        return when {
            name == "libamxmodx.so" -> "amxx"
            name == "libmetamod.so" || name.startsWith("libmetamod_android_") -> "metamod"
            name == "libyapb.so" -> "yapb"
            name.startsWith("libclient_android_") -> "client"
            name.startsWith("libmenu_android_") -> "menu"
            name.startsWith("libcs_android_") -> "game"
            name.contains("_amxx_") -> "modules"
            else -> "other"
        }
    }

    fun patchComponents(): List<PatchComponent> {
        val b = loadedBundle ?: return emptyList()
        val order = listOf("amxx", "metamod", "yapb", "client", "menu", "modules", "game", "other")
        return b.manifest.entries
            .groupBy { componentKeyFor(it.target) }
            .mapNotNull { (key, entries) ->
                PatchComponent(
                    key = key,
                    label = labelForComponent(key),
                    description = entries.joinToString(", ") {
                        it.target.substringAfterLast("/")
                    },
                    entryTargets = entries.map { it.target },
                )
            }
            .sortedBy { order.indexOf(it.key) }
    }
}
