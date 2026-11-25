package com.yoriva.patcher

import android.app.Application
import com.yoriva.data.BundleProvider
import com.yoriva.data.IncrementalUpdateManager
import com.yoriva.lib.Bundle
import com.yoriva.lib.BundleManifest
import com.yoriva.patcher.LibInfo
import com.yoriva.patcher.PatcherViewModel.CustomLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class BundleManager(
    private val app: Application,
    private val bundleProvider: BundleProvider,
    private val scope: CoroutineScope,
    private val abiProvider: () -> String,
    private val appliedBuilds: (String) -> Map<String, String>,
    private val recordAppliedBuilds: (String, List<IncrementalUpdateManager.AssetInfo>) -> Unit,
    private val markBundleTagKnown: (String) -> Unit,
    private val refreshAbiStatus: () -> Unit,
    private val externalRoot: File,
) {

    val libsDir = File(externalRoot, "libs")

    var loadedBundle: Bundle? = null
    var downloadingAbi: String? = null

    private val _bundle = MutableStateFlow<BundleState>(BundleState.None)
    val bundle: StateFlow<BundleState> = _bundle.asStateFlow()

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

    internal fun resetAll() {
        loadedBundle = null
        downloadingAbi = null
        _bundle.value = BundleState.None
        _libs.value = emptyList()
        _customLibs.value = emptyList()
        _customLibProblem.value = null
        autoExcluded = emptySet()
        applyExclusions()
    }

    fun useCachedBundle() {
        val abi = abiProvider()
        val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
        if (files.isNotEmpty()) {
            val b = buildBundleFromFileMap(files, abi)
            loadedBundle = b
            _bundle.value = BundleState.Loaded
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

    private val cachedCoreAssets = ConcurrentHashMap<String, List<IncrementalUpdateManager.AssetInfo>>()
    private val cachedPluginAssets = ConcurrentHashMap<String, List<IncrementalUpdateManager.AssetInfo>>()
    private val lastCheckAtMs = ConcurrentHashMap<String, Long>()
    private val singleLibJobs = ConcurrentHashMap<String, Job>()

    private val _libsError = MutableStateFlow<String?>(null)
    val libsError: StateFlow<String?> = _libsError.asStateFlow()

    fun dismissLibsError() {
        _libsError.value = null
    }

    fun scanLibs(autoLoad: Boolean = false, checkRemote: Boolean = false, force: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val abi = abiProvider()
            val targetDir = File(libsDir, abi)
            val localFiles = if (targetDir.isDirectory) {
                targetDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.associateBy { it.name }
                    ?: emptyMap()
            } else emptyMap()

            var coreAssets = cachedCoreAssets[abi].orEmpty()
            var pluginAssets = cachedPluginAssets[abi].orEmpty()

            if (checkRemote) {
                val now = System.currentTimeMillis()
                val lastCheck = lastCheckAtMs[abi] ?: 0L
                val isCacheFresh = !force && coreAssets.isNotEmpty() && (now - lastCheck) < REMOTE_CHECK_COOLDOWN_MS
                if (!isCacheFresh) {
                    _libsChecking.value = true
                    var coreError: Throwable? = null
                    var pluginError: Throwable? = null
                    try {
                        val fetchedCore = try {
                            IncrementalUpdateManager.fetchReleaseAssets(PatcherViewModel.ASSETS_TAG, abi)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            coreError = t
                            null
                        }
                        if (fetchedCore != null && fetchedCore.isNotEmpty()) {
                            coreAssets = fetchedCore
                            cachedCoreAssets[abi] = fetchedCore
                        }
                        val fetchedPlugins = try {
                            IncrementalUpdateManager.fetchPluginAssets(abi)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            pluginError = t
                            null
                        }
                        if (fetchedPlugins != null) {
                            pluginAssets = fetchedPlugins
                            cachedPluginAssets[abi] = fetchedPlugins
                        }
                        val err = coreError ?: pluginError
                        if (coreError == null && pluginError == null) {
                            lastCheckAtMs[abi] = System.currentTimeMillis()
                            _libsError.value = null
                        } else if (force || (coreAssets.isEmpty() && pluginAssets.isEmpty())) {
                            _libsError.value = err?.message ?: "Failed to check library updates"
                        }
                    } finally {
                        _libsChecking.value = false
                    }
                }
            }

            rebuildLibState(abi, targetDir, localFiles, coreAssets, pluginAssets)

            if (checkRemote || coreAssets.isNotEmpty()) {
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
                        // Optional plugins that are not installed are never treated as broken/invalid.
                        val isInvalid = when (lib.source) {
                            LibSource.CORE -> lib.localSize == 0L || !lib.upToDate
                            LibSource.PLUGIN -> lib.localSize > 0L && !lib.upToDate
                        }
                        if (isInvalid) invalid.add(lib.name)
                    }
                    val nextAuto = (autoExcluded - checkedNames) + invalid
                    if (nextAuto != autoExcluded) {
                        autoExcluded = nextAuto
                        applyExclusions()
                        if (loadedBundle != null && localFiles.isNotEmpty()) rebuildLoadedBundleFiles()
                    }
                    if (coreChecked) {
                        _coreOutdatedCount.value = _libs.value.count {
                            it.source == LibSource.CORE && !it.upToDate && it.releaseSize > 0
                        }
                    }
                }
            }
            val coreOutdated = _libs.value.filter { it.source == LibSource.CORE && !it.upToDate && it.releaseSize > 0 }
            if (autoLoad && coreOutdated.isEmpty() && coreAssets.isNotEmpty()) {
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

    private fun rebuildLibState(
        abi: String,
        targetDir: File,
        localFiles: Map<String, File>,
        coreAssets: List<IncrementalUpdateManager.AssetInfo>,
        pluginAssets: List<IncrementalUpdateManager.AssetInfo>,
    ) {
        val coreMap = coreAssets.associateBy { it.cleanName }
        val pluginMap = pluginAssets.associateBy { it.cleanName }
        val prevMap = _libs.value.associateBy { it.name }
        val allNames = (localFiles.keys + coreMap.keys + pluginMap.keys).distinct().sorted()
        _libs.value = allNames.map { name ->
            val f = localFiles[name]
            val asset = coreMap[name] ?: pluginMap[name]
            val source = if (pluginMap.containsKey(name) || isPluginLibName(name, abi)) {
                LibSource.PLUGIN
            } else {
                LibSource.CORE
            }
            val prev = prevMap[name]
            LibInfo(
                name = name,
                localSize = f?.length() ?: 0L,
                releaseSize = asset?.size ?: (f?.length() ?: 0L),
                upToDate = if (asset != null) {
                    IncrementalUpdateManager.isUpToDate(
                        File(targetDir, name), asset, appliedBuilds(abi)[name],
                    )
                } else true,
                downloading = prev?.downloading == true,
                downloadProgress = if (prev?.downloading == true) prev.downloadProgress else -1f,
                source = source,
            )
        }
    }

    private fun isPluginLibName(name: String, abi: String): Boolean {
        val suffix = if (abi == "arm64-v8a") "arm64" else "arm"
        return name.endsWith("_yoriva_${suffix}.so")
    }

    private var bundleJob: Job? = null
    private var pluginsJob: Job? = null

    private val _pluginsDownloading = MutableStateFlow(false)
    val pluginsDownloading: StateFlow<Boolean> = _pluginsDownloading.asStateFlow()

    private val _coreOutdatedCount = MutableStateFlow(0)
    val coreOutdatedCount: StateFlow<Int> = _coreOutdatedCount.asStateFlow()

    fun cancelBundleDownload() {
        bundleJob?.cancel()
        bundleJob = null
        downloadingAbi = null
        _libs.value = _libs.value.map {
            if (it.source == LibSource.CORE && it.downloading) {
                it.copy(downloading = false, downloadProgress = -1f)
            } else it
        }
        if (_bundle.value is BundleState.Downloading) {
            _bundle.value = if (loadedBundle != null) BundleState.Loaded else BundleState.None
        }
        refreshAbiStatus()
    }

    fun cancelSingleLib(libName: String) {
        singleLibJobs.remove(libName)?.cancel()
        _libs.value = _libs.value.map {
            if (it.name == libName) it.copy(downloading = false, downloadProgress = -1f) else it
        }
    }

    fun cancelPluginDownloads() {
        pluginsJob?.cancel()
        pluginsJob = null
        _pluginsDownloading.value = false
        _libs.value = _libs.value.map {
            if (it.source == LibSource.PLUGIN && it.downloading) {
                it.copy(downloading = false, downloadProgress = -1f)
            } else it
        }
    }

    fun cancelAllDownloads() {
        singleLibJobs.keys.toList().forEach { key -> singleLibJobs.remove(key)?.cancel() }
        cancelBundleDownload()
        cancelPluginDownloads()
    }

    fun refreshLibStatus(force: Boolean = true) {
        scanLibs(checkRemote = true, force = force)
    }

    fun refreshSingleLib(libName: String) {
        if (singleLibJobs[libName]?.isActive == true) return
        val job = scope.launch(Dispatchers.IO) {
            try {
                downloadLib(libName)
            } finally {
                singleLibJobs.remove(libName)
            }
        }
        singleLibJobs[libName] = job
    }

    /**
     * Downloads any missing or outdated optional Yoriva plugins from `Yoriva/yoriva-plugins`.
     */
    fun downloadOptionalPlugins() {
        if (pluginsJob?.isActive == true) return
        _pluginsDownloading.value = true
        _libsError.value = null
        pluginsJob = scope.launch(Dispatchers.IO) {
            try {
                val abi = abiProvider()
                val pluginAssets = IncrementalUpdateManager.fetchPluginAssets(abi)
                cachedPluginAssets[abi] = pluginAssets
                val diff = IncrementalUpdateManager.diff(pluginAssets, libsDir, abi, appliedBuilds(abi))
                for (asset in diff.toDownload) {
                    downloadLib(asset.cleanName, prefetchedAssets = pluginAssets)
                }
            } catch (_: CancellationException) {
                _libs.value = _libs.value.map {
                    if (it.source == LibSource.PLUGIN && it.downloading) {
                        it.copy(downloading = false, downloadProgress = -1f)
                    } else it
                }
            } catch (t: Throwable) {
                _libsError.value = t.message ?: "Failed to download Yoriva Plugins"
            } finally {
                _pluginsDownloading.value = false
                scanLibs()
            }
        }
    }

    private suspend fun resolveAssetsFor(
        libName: String,
        abi: String,
    ): List<IncrementalUpdateManager.AssetInfo> {
        val isPlugin = isPluginLibName(libName, abi) ||
            _libs.value.firstOrNull { it.name == libName }?.source == LibSource.PLUGIN
        return if (isPlugin) {
            cachedPluginAssets[abi]?.takeIf { list -> list.any { it.cleanName == libName } }
                ?: IncrementalUpdateManager.fetchPluginAssets(abi).also { cachedPluginAssets[abi] = it }
        } else {
            cachedCoreAssets[abi]?.takeIf { list -> list.any { it.cleanName == libName } }
                ?: IncrementalUpdateManager.fetchReleaseAssets(PatcherViewModel.ASSETS_TAG, abi).also {
                    cachedCoreAssets[abi] = it
                }
        }
    }

    private fun enableDownloadedLib(libName: String) {
        clearAutoExcluded(setOf(libName))
        val manual = manualExcluded.value - libName
        if (manual != manualExcluded.value) {
            manualExcluded.value = manual
            excludedPrefs.edit().putStringSet("excluded", HashSet(manual)).apply()
            applyExclusions()
        }
    }

    private suspend fun downloadLib(
        libName: String,
        prefetchedAssets: List<IncrementalUpdateManager.AssetInfo>? = null,
    ) {
        try {
            _libsError.value = null
            markLibDownloading(libName, 0f)
            val abi = abiProvider()
            val assets = prefetchedAssets ?: resolveAssetsFor(libName, abi)
            val asset = assets.find { it.cleanName == libName }
                ?: throw IOException("Asset metadata not found for $libName")
            IncrementalUpdateManager.downloadSingle(asset, libsDir, abi) { p ->
                markLibDownloading(libName, p)
            }
            val fileOnDisk = File(File(libsDir, abi), libName)
            val newSize = if (fileOnDisk.exists()) fileOnDisk.length() else 0L
            val upToDate = IncrementalUpdateManager.isUpToDate(
                fileOnDisk, asset, appliedBuilds(abi)[libName]
            )
            if (!upToDate) {
                fileOnDisk.delete()
                throw IOException("Downloaded $libName could not be verified")
            }
            recordAppliedBuilds(abi, listOf(asset))
            _libs.value = _libs.value.map {
                if (it.name == libName) {
                    it.copy(
                        localSize = newSize,
                        releaseSize = asset.size.takeIf { s -> s > 0 } ?: newSize,
                        upToDate = true,
                        downloading = false,
                        downloadProgress = -1f,
                    )
                } else it
            }
            enableDownloadedLib(libName)
            rebuildLoadedBundleFiles()
            _coreOutdatedCount.value = _libs.value.count {
                it.source == LibSource.CORE && !it.upToDate && it.releaseSize > 0
            }
        } catch (c: CancellationException) {
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = false, downloadProgress = -1f) else it
            }
            throw c
        } catch (t: Throwable) {
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = false, downloadProgress = -1f) else it
            }
            _libsError.value = "$libName: ${t.message ?: "Download failed"}"
        }
    }

    fun fetchAndDownloadBundle() {
        if (bundleJob?.isActive == true) return
        bundleJob = scope.launch(Dispatchers.IO) {
            try {
                fetchAndDownloadBundleNow()
            } catch (_: CancellationException) {
            }
        }
    }

    /**
     * Incrementally syncs only required core mod libraries ([LibSource.CORE]).
     * Optional Yoriva Plugins are left untouched and downloaded on-demand from Libraries.
     */
    suspend fun fetchAndDownloadBundleNow(
        reportJob: Boolean = true,
        onByteProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        if (_bundle.value is BundleState.Downloading) return false
        val abi = abiProvider()
        downloadingAbi = abi
        refreshAbiStatus()
        _bundle.value = BundleState.Downloading(0.04f)
        var jobStarted = false
        return try {
            val tagName = PatcherViewModel.ASSETS_TAG
            val coreAssets = IncrementalUpdateManager.fetchReleaseAssets(tagName, abi)
            if (coreAssets.isEmpty()) throw IOException("No .so assets found for ABI $abi")
            cachedCoreAssets[abi] = coreAssets
            _bundle.update { BundleState.Downloading(0.1f, tagName) }
            val diff = IncrementalUpdateManager.diff(
                coreAssets, libsDir, abi, appliedBuilds(abi)
            )
            if (diff.toDownload.isEmpty()) {
                clearAutoExcluded(coreAssets.map { it.cleanName }.toSet())
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
                if (files.isEmpty()) throw IOException("No .so files found in libs/")
                val b = buildBundleFromFileMap(files, abi)
                markBundleTagKnown(tagName)
                recordAppliedBuilds(abi, coreAssets)
                _coreOutdatedCount.value = 0
                _bundle.value = BundleState.Ready(
                    "Libraries up to date · ${b.manifest.entries.size} files",
                    b.manifest.entries.size,
                    b.manifest.version,
                )
                loadedBundle = b
                downloadingAbi = null
                scanLibs()
                refreshAbiStatus()
                return true
            }
            if (reportJob) {
                com.yoriva.jobs.JobProgress.begin(app, PatcherViewModel.JOB_LIBS, "Libraries", "Downloading ${diff.toDownload.size} files")
                jobStarted = true
            }
            IncrementalUpdateManager.downloadChanged(
                diff.toDownload,
                libsDir,
                abi,
                onFileStart = { index, asset ->
                    markLibDownloading(asset.cleanName, 0f)
                    if (jobStarted) {
                        com.yoriva.jobs.JobProgress.detail(app, PatcherViewModel.JOB_LIBS, asset.cleanName)
                    }
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
                    if (jobStarted) {
                        com.yoriva.jobs.JobProgress.progress(
                            app,
                            PatcherViewModel.JOB_LIBS,
                            (overall * 100).toLong().coerceIn(0L, 99L),
                            100L,
                            "${asset.cleanName} ${(fileProgress * 100).toInt().coerceIn(0, 100)}%",
                        )
                    }
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
                onProgress = { _, _, bytesWritten ->
                    onByteProgress?.invoke(bytesWritten, diff.totalBytes)
                },
            )
            clearAutoExcluded(diff.toDownload.map { it.cleanName }.toSet())
            val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
            if (files.isEmpty()) throw IOException("No .so files found after download")
            val b = buildBundleFromFileMap(files, abi)
            markBundleTagKnown(tagName)
            recordAppliedBuilds(abi, coreAssets)
            _bundle.value = BundleState.Ready(
                "Updated ${diff.toDownload.size} files",
                b.manifest.entries.size,
                b.manifest.version,
            )
            loadedBundle = b
            downloadingAbi = null
            _coreOutdatedCount.value = 0
            scanLibs()
            refreshAbiStatus()
            if (jobStarted) {
                com.yoriva.jobs.JobProgress.finish(app, PatcherViewModel.JOB_LIBS, "Libraries ready")
            }
            true
        } catch (c: CancellationException) {
            downloadingAbi = null
            _libs.value = _libs.value.map {
                if (it.downloading) it.copy(downloading = false, downloadProgress = -1f) else it
            }
            _bundle.value = if (loadedBundle != null) BundleState.Loaded else BundleState.None
            if (jobStarted) com.yoriva.jobs.JobProgress.clear(PatcherViewModel.JOB_LIBS)
            scanLibs()
            refreshAbiStatus()
            throw c
        } catch (t: Throwable) {
            val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, abi, _libExcluded.value)
            if (files.isNotEmpty() && loadedBundle == null) {
                val b = buildBundleFromFileMap(files, abi)
                loadedBundle = b
                _bundle.value = BundleState.Loaded
                scanLibs()
                if (jobStarted) {
                    com.yoriva.jobs.JobProgress.finish(app, PatcherViewModel.JOB_LIBS, "Using the libraries already on disk")
                }
            } else {
                _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
                if (jobStarted) {
                    com.yoriva.jobs.JobProgress.finish(app, PatcherViewModel.JOB_LIBS, t.message ?: "Download failed", ok = false)
                }
            }
            downloadingAbi = null
            refreshAbiStatus()
            false
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

    companion object {
        private const val REMOTE_CHECK_COOLDOWN_MS = 20_000L
    }
}
