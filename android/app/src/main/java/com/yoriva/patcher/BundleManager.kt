package com.yoriva.patcher

import android.app.Application
import com.yoriva.data.BundleProvider
import com.yoriva.data.IncrementalUpdateManager
import com.yoriva.lib.Bundle
import com.yoriva.lib.BundleManifest
import com.yoriva.patcher.LibInfo
import com.yoriva.patcher.PatcherViewModel.CustomLib
import com.yoriva.patcher.PatcherViewModel.PatchComponent
import com.yoriva.patcher.BundleState
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
    private val gameDirProvider: () -> File,
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
    private val excludedPrefs by lazy {
        app.getSharedPreferences("nexora_libs", android.content.Context.MODE_PRIVATE)
    }
    private fun loadExcluded(key: String): Set<String> =
        excludedPrefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private val manualExcluded = MutableStateFlow(loadExcluded("excluded"))
    private val includedOverrides = MutableStateFlow(loadExcluded("included_overrides"))
    private var autoExcluded: Set<String> = emptySet()
    private val _libExcluded = MutableStateFlow(manualExcluded.value - includedOverrides.value)
    val libExcluded: StateFlow<Set<String>> = _libExcluded.asStateFlow()

    private fun applyExclusions() {
        val next = (manualExcluded.value + autoExcluded) - includedOverrides.value
        if (next != _libExcluded.value) _libExcluded.value = next
    }

    private fun clearAutoExcluded(names: Set<String>) {
        if (names.isEmpty()) return
        val next = autoExcluded - names
        if (next == autoExcluded) return
        autoExcluded = next
        applyExclusions()
    }

    fun setLibExcluded(name: String, excluded: Boolean) {
        val manual = manualExcluded.value.toMutableSet()
        val overrides = includedOverrides.value.toMutableSet()
        if (excluded) {
            manual.add(name)
            overrides.remove(name)
        } else {
            manual.remove(name)
            overrides.add(name)
        }
        if (manual == manualExcluded.value && overrides == includedOverrides.value) return
        manualExcluded.value = manual
        includedOverrides.value = overrides
        excludedPrefs.edit()
            .putStringSet("excluded", HashSet(manual))
            .putStringSet("included_overrides", HashSet(overrides))
            .apply()
        applyExclusions()
        rebuildLoadedBundle()
    }

    fun toggleLibExcluded(name: String) = setLibExcluded(name, name !in _libExcluded.value)

    private fun rebuildLoadedBundleFiles() {
        val abi = abiProvider()
        val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
        loadedBundle = buildBundleFromFileMap(files, abi)
        refreshAbiStatus()
    }

    private fun rebuildLoadedBundle() {
        rebuildLoadedBundleFiles()
        scanLibs()
    }

    private val _customLibProblem = MutableStateFlow<String?>(null)
    val customLibProblem: StateFlow<String?> = _customLibProblem.asStateFlow()

    val hasCachedBundle: Boolean get() = bundleProvider.hasCachedBundle(abiProvider())

    internal fun clearLoadedBundle() {
        loadedBundle = null
        _bundle.value = BundleState.None
    }

    fun clearLocalLibraryCache() {
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            File(libsDir, abi).listFiles()?.forEach { it.deleteRecursively() }
        }
        resetAll()
    }

    internal fun resetAll() {
        loadedBundle = null
        downloadingAbi = null
        _bundle.value = BundleState.None
        _bundleVersion.value = ""
        _libs.value = emptyList()
        _customLibs.value = emptyList()
        _customLibProblem.value = null
        autoExcluded = emptySet()
        applyExclusions()
    }

    internal fun setBundleVersion(version: String) {
        _bundleVersion.value = version
    }

    fun useCachedBundle() {
        val abi = abiProvider()
        val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
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
            var coreAssets: List<IncrementalUpdateManager.AssetInfo> = emptyList()
            var pluginAssets: List<IncrementalUpdateManager.AssetInfo> = emptyList()
            if (checkRemote) {
                _libsChecking.value = true
                try {
                    coreAssets = try {
                        IncrementalUpdateManager.fetchReleaseAssets(PatcherViewModel.ASSETS_TAG, abi)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    pluginAssets = try {
                        IncrementalUpdateManager.fetchPluginAssets(abi)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                } finally {
                    _libsChecking.value = false
                }
            }
            val coreMap = coreAssets.associateBy { it.cleanName }
            val pluginMap = pluginAssets.associateBy { it.cleanName }
            val allNames = (localFiles.keys + coreMap.keys + pluginMap.keys).distinct().sorted()
            _libs.value = allNames.map { name ->
                val f = localFiles[name]
                val asset = coreMap[name] ?: pluginMap[name]
                val source = if (pluginMap.containsKey(name) || isPluginLibName(name, abi)) {
                    LibSource.PLUGIN
                } else {
                    LibSource.CORE
                }
                LibInfo(
                    name = name,
                    localSize = f?.length() ?: 0L,
                    releaseSize = asset?.size ?: (f?.length() ?: 0L),
                    upToDate = if (asset != null) {
                        IncrementalUpdateManager.isUpToDate(
                            File(targetDir, name), asset, appliedBuilds(abi)[name]
                        )
                    } else true,
                    source = source,
                )
            }
            if (checkRemote) {
                val coreChecked = coreAssets.isNotEmpty()
                val pluginChecked = pluginAssets.isNotEmpty()
                if (coreChecked || pluginChecked) {
                    val checkedNames = mutableSetOf<String>()
                    val invalid = mutableSetOf<String>()
                    _libs.value.forEach { lib ->
                        val checked = when (lib.source) {
                            LibSource.CORE -> coreChecked
                            LibSource.PLUGIN -> pluginChecked
                        }
                        if (!checked) return@forEach
                        checkedNames.add(lib.name)
                        if (lib.localSize == 0L || !lib.upToDate) invalid.add(lib.name)
                    }
                    val nextAuto = (autoExcluded - checkedNames) + invalid
                    if (nextAuto != autoExcluded) {
                        autoExcluded = nextAuto
                        applyExclusions()
                        if (loadedBundle != null && localFiles.isNotEmpty()) rebuildLoadedBundleFiles()
                    }
                }
            }
            val outdated = _libs.value.filter { !it.upToDate && it.releaseSize > 0 }
            if (autoLoad && outdated.isEmpty() && (coreAssets.isNotEmpty() || pluginAssets.isNotEmpty())) {
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, abi)
                    markBundleTagKnown(PatcherViewModel.ASSETS_TAG)
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                }
            }
        }
    }

    private fun isPluginLibName(name: String, abi: String): Boolean {
        val suffix = if (abi == "arm64-v8a") "arm64" else "arm"
        return name.endsWith("_yoriva_${suffix}.so")
    }

    fun refreshLibStatus() {
        scanLibs(checkRemote = true)
    }

    fun refreshSingleLib(libName: String) {
        val current = _libs.value.firstOrNull { it.name == libName }
        if (current?.downloading == true) return
        scope.launch(Dispatchers.IO) {
            downloadLib(libName)
        }
    }

    private suspend fun fetchAllAssets(abi: String): List<IncrementalUpdateManager.AssetInfo> {
        val core: List<IncrementalUpdateManager.AssetInfo> = try {
            IncrementalUpdateManager.fetchReleaseAssets(PatcherViewModel.ASSETS_TAG, abi)
        } catch (_: Throwable) {
            emptyList()
        }
        val plugins: List<IncrementalUpdateManager.AssetInfo> = try {
            IncrementalUpdateManager.fetchPluginAssets(abi)
        } catch (_: Throwable) {
            emptyList()
        }
        return core + plugins
    }

    private suspend fun downloadLib(libName: String) {
        try {
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = true, downloadProgress = 0f) else it
            }
            val assets = fetchAllAssets(abiProvider())
            val asset = assets.find { it.cleanName == libName } ?: run {
                _libs.value = _libs.value.map {
                    if (it.name == libName) it.copy(downloading = false) else it
                }
                return
            }
            IncrementalUpdateManager.downloadSingle(asset, libsDir, abiProvider()) { p ->
                _libs.value = _libs.value.map {
                    if (it.name == libName) it.copy(downloading = true, downloadProgress = p) else it
                }
            }
            val fileOnDisk = File(File(libsDir, abiProvider()), libName)
            val newSize = if (fileOnDisk.exists()) fileOnDisk.length() else 0L
            if (asset.size > 0 && fileOnDisk.exists() && fileOnDisk.length() != asset.size) {
                fileOnDisk.delete()
                _libs.value = _libs.value.map {
                    if (it.name == libName) it.copy(downloading = false, upToDate = false) else it
                }
                return
            }
            val upToDate = IncrementalUpdateManager.isUpToDate(
                fileOnDisk, asset, appliedBuilds(abiProvider())[libName]
            )
            recordAppliedBuilds(abiProvider(), listOf(asset))
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(localSize = newSize, upToDate = upToDate) else it
            }
            if (upToDate) {
                clearAutoExcluded(setOf(libName))
                rebuildLoadedBundleFiles()
            }
        } catch (t: Throwable) {
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = false) else it
            }
        }
    }

    fun fetchAndDownloadBundle() {
        scope.launch(Dispatchers.IO) { fetchAndDownloadBundleNow() }
    }

    suspend fun fetchAndDownloadBundleNow(): Boolean {
        if (_bundle.value is BundleState.Downloading) return false
        downloadingAbi = abiProvider()
        refreshAbiStatus()
        _bundle.value = BundleState.Downloading(0.04f)
        var jobStarted = false
        return try {
            val tagName = PatcherViewModel.ASSETS_TAG
            setReleaseNote(tagName)
            val coreAssets = IncrementalUpdateManager.fetchReleaseAssets(tagName, abiProvider())
            if (coreAssets.isEmpty()) throw IOException("No .so assets found for ABI ${abiProvider()}")
            val pluginAssets: List<IncrementalUpdateManager.AssetInfo> = try {
                IncrementalUpdateManager.fetchPluginAssets(abiProvider())
            } catch (_: Throwable) {
                emptyList()
            }
            val assets = coreAssets + pluginAssets
            _bundle.update { BundleState.Downloading(0.1f, tagName) }
            val diff = IncrementalUpdateManager.diff(
                assets, libsDir, abiProvider(), appliedBuilds(abiProvider())
            )
            if (diff.toDownload.isEmpty()) {
                clearAutoExcluded(assets.map { it.cleanName }.toSet())
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abiProvider(), _libExcluded.value)
                if (files.isEmpty()) throw IOException("No .so files found in libs/")
                val b = buildBundleFromFileMap(files, abiProvider())
                markBundleTagKnown(tagName)
                recordAppliedBuilds(abiProvider(), assets)
                val installed = installIntoGameDir(b)
                _bundle.value = BundleState.Ready(
                    gameDirMessage("Bundle is up to date", installed),
                    b.manifest.entries.size,
                    b.manifest.version,
                )
                loadedBundle = b
                _bundleVersion.value = b.manifest.version
                scanLibs()
                return true
            }
            com.yoriva.jobs.JobProgress.begin(app, com.yoriva.patcher.PatcherViewModel.JOB_LIBS, "Libraries", "Downloading ${diff.toDownload.size} files")
            jobStarted = true
            IncrementalUpdateManager.downloadChanged(
                diff.toDownload, libsDir, abiProvider(),
                onFileStart = { index, asset ->
                    markLibDownloading(asset.cleanName, 0f)
                    com.yoriva.jobs.JobProgress.detail(app, com.yoriva.patcher.PatcherViewModel.JOB_LIBS, asset.cleanName)
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
                    com.yoriva.jobs.JobProgress.progress(
                        app,
                        com.yoriva.patcher.PatcherViewModel.JOB_LIBS,
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
            val targetDir = File(libsDir, abiProvider())
            for (asset in diff.toDownload) {
                val f = File(targetDir, asset.cleanName)
                if (asset.size > 0 && f.exists() && f.length() != asset.size) {
                    val got = f.length()
                    f.delete()
                    throw IOException("${asset.cleanName} came out short ($got of ${asset.size} bytes) — try again")
                }
            }
            clearAutoExcluded(diff.toDownload.map { it.cleanName }.toSet())
            val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abiProvider(), _libExcluded.value)
            if (files.isEmpty()) throw IOException("No .so files found after download")
            val b = buildBundleFromFileMap(files, abiProvider())
            markBundleTagKnown(tagName)
            recordAppliedBuilds(abiProvider(), assets)
            val installed = installIntoGameDir(b)
            _bundle.value = BundleState.Ready(
                gameDirMessage("Updated ${diff.toDownload.size} files", installed),
                b.manifest.entries.size,
                b.manifest.version,
            )
            loadedBundle = b
            downloadingAbi = null
            _bundleVersion.value = b.manifest.version
            scanLibs()
            refreshAbiStatus()
            com.yoriva.jobs.JobProgress.finish(app, com.yoriva.patcher.PatcherViewModel.JOB_LIBS, "Libraries ready")
            true
        } catch (t: Throwable) {
            val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abiProvider(), _libExcluded.value)
            if (files.isNotEmpty()) {
                val b = buildBundleFromFileMap(files, abiProvider())
                loadedBundle = b
                _bundle.value = BundleState.Loaded
                scanLibs()
                if (jobStarted) {
                    com.yoriva.jobs.JobProgress.finish(app, com.yoriva.patcher.PatcherViewModel.JOB_LIBS, "Using the libraries already on disk")
                }
            } else {
                _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
                if (jobStarted) {
                    com.yoriva.jobs.JobProgress.finish(app, com.yoriva.patcher.PatcherViewModel.JOB_LIBS, t.message ?: "Download failed", ok = false)
                }
            }
            downloadingAbi = null
            refreshAbiStatus()
            false
        }
    }

    private fun installIntoGameDir(bundle: Bundle): Int? {
        val gameDir = gameDirProvider()
        if (!gameDir.isDirectory) return null
        wipeObsoleteGameDlls(gameDir)
        var count = 0
        bundle.manifest.entries.forEach { entry ->
            val relative = gameInstallPathFor(entry.target) ?: return@forEach
            val source = bundle.diskFiles[entry.source]
                ?: bundle.diskFiles[entry.target]
                ?: return@forEach
            if (!source.isFile) return@forEach
            val dest = File(gameDir, relative)
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
            dest.setExecutable(true)
            count++
        }
        return count
    }

    private fun gameDirMessage(base: String, installed: Int?): String = when {
        installed == null -> "$base · cstrike folder not found"
        installed == 0 -> "$base · nothing to place in cstrike"
        else -> "$base · $installed files placed in cstrike"
    }

    private fun gameInstallPathFor(target: String): String? {
        val name = target.substringAfterLast('/')
        if (name.endsWith(".so")) {
            return null
        }
        return null
    }

    private fun wipeObsoleteGameDlls(gameDir: File) {
        val targets = listOf(
            "addons/yapb/bin",
            "addons/amxmodx/modules",
            "addons/amxmodx",
            "addons/metamod",
            "cl_dlls",
            "dlls",
        )
        for (rel in targets) {
            File(gameDir, rel).listFiles { f -> f.isFile && f.name.endsWith(".so") }
                ?.forEach { it.delete() }
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
                name == "libmetadroid.so" || name.startsWith("libmetadroid_android_") -> "Metamod HL1"
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
            when (val verdict = com.yoriva.lib.validateElf(target, abi)) {
                is com.yoriva.lib.ElfVerdict.Rejected -> {
                    target.delete()
                    existing.remove(name)
                    rejected += "$name: ${verdict.reason}"
                }
                is com.yoriva.lib.ElfVerdict.Ok -> {
                    val machine = com.yoriva.lib.readElfInfo(target)?.machineName ?: "?"
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
            when (val verdict = com.yoriva.lib.validateElf(file, abi)) {
                is com.yoriva.lib.ElfVerdict.Ok -> CustomLib(
                    name = file.name,
                    file = file,
                    machineName = com.yoriva.lib.readElfInfo(file)?.machineName ?: "?",
                    shadows = shadowedBy("lib/$abi/${file.name}"),
                )
                else -> null
            }
        }
        _customLibs.value = loaded
    }

    fun customLibEntries(bundle: Bundle, selected: Set<String>): List<Pair<String, File>> =
        _customLibs.value
            .filter { it.name !in _libExcluded.value }
            .map { "lib/${abiProvider()}/${it.name}" to it.file }

    private fun labelForComponent(key: String): String = when (key) {
        "amxx" -> "AMX Mod X core"
        "metamod" -> "Metamod HL1"
        "yapb" -> "YaPB bot plugin"
        "client" -> "CS16Client client DLL"
        "menu" -> "CS16Client main menu"
        "game" -> "ReGameDLL game DLL"
        "modules" -> "AMXX modules"
        "plugins" -> "Yoriva Plugins"
        else -> "Bundle files"
    }

    fun staleCustomLibs(): List<CustomLib> {
        val abi = abiProvider()
        return _customLibs.value.filter {
            val verdict = com.yoriva.lib.validateElf(it.file, abi)
            verdict !is com.yoriva.lib.ElfVerdict.Ok
        }
    }

    internal fun componentKeyFor(target: String): String {
        val name = target.substringAfterLast("/")
        return when {
            name == "libamxmodx.so" -> "amxx"
            name == "libmetadroid.so" || name.startsWith("libmetadroid_android_") -> "metamod"
            name == "libyapb.so" -> "yapb"
            name.startsWith("libclient_android_") -> "client"
            name.startsWith("libmenu_android_") -> "menu"
            name.startsWith("libcs_android_") -> "game"
            name.contains("_amxx_") -> "modules"
            name.contains("_yoriva_") -> "plugins"
            else -> "other"
        }
    }

    fun patchComponents(): List<PatchComponent> {
        val b = loadedBundle ?: return emptyList()
        val order = listOf("amxx", "metamod", "yapb", "client", "menu", "modules", "plugins", "game", "other")
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
