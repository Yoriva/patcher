package com.yoriva.patcher
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yoriva.data.BundleProvider
import com.yoriva.data.YORIVA_ROOT
import com.yoriva.lib.ApkPatcher
import com.yoriva.lib.Bundle
import com.yoriva.lib.SigningKeystore
import com.yoriva.lib.ZipAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
data class SourceInfo(
    val name: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val abis: List<String> = emptyList(),
)
sealed interface SourceDownloadState {
    data object Fetching : SourceDownloadState
    data class Downloading(val downloaded: Long, val total: Long) : SourceDownloadState
    data class Done(val name: String) : SourceDownloadState
    data class Failed(val message: String) : SourceDownloadState
    data class Outdated(
        val name: String,
        val localSize: Long,
        val remoteSize: Long,
        val reason: String,
    ) : SourceDownloadState
    data class Corrupt(val name: String, val reason: String) : SourceDownloadState
}
sealed interface BundleState {
    data object None : BundleState
    data object Loaded : BundleState
    data class Ready(val bundleName: String, val entries: Int, val version: String) : BundleState
    data class Downloading(
        val percent: Float,
        val tagName: String = "",
        val currentFile: String = "",
        val fileIndex: Int = 0,
        val fileTotal: Int = 0,
    ) : BundleState
    data class DownloadError(val message: String) : BundleState
}
sealed interface AddonsState {
    data object None : AddonsState
    data class Downloading(val percent: Float, val step: String) : AddonsState
    data class Done(val message: String) : AddonsState
    data class Error(val message: String) : AddonsState
}
sealed interface PatchUiState {
    data object Idle : PatchUiState
    data class Running(
        val step: ApkPatcher.Step,
        val progress: Float,
        val detail: String = "",
        val index: Int = 0,
        val total: Int = 0,
        val log: List<String> = emptyList(),
        val counters: Map<String, String> = emptyMap(),
    ) : PatchUiState
    data class Done(
        val report: ApkPatcher.PatchReport,
        val log: List<String> = emptyList(),
    ) : PatchUiState
    data class Failed(val message: String) : PatchUiState
}
sealed interface CleanupState {
    data object Idle : CleanupState
    data object Running : CleanupState
    data class Done(val removed: Int) : CleanupState
    data class Failed(val message: String) : CleanupState
}
enum class LibSource { CORE, PLUGIN }
data class LibInfo(
    val name: String,
    val localSize: Long,
    val releaseSize: Long,
    val upToDate: Boolean,
    val downloading: Boolean = false,
    val downloadProgress: Float = -1f,
    val source: LibSource = LibSource.CORE,
)
class PatcherViewModel(app: Application) : AndroidViewModel(app) {
    private val bundleProvider = BundleProvider(app)
    private val app: Application = app
    val source: StateFlow<SourceInfo?> get() = sourceManager.source
    val sourceDownload: StateFlow<SourceDownloadState?> get() = sourceManager.sourceDownload
    val bundle: StateFlow<BundleState> get() = bundleManager.bundle
    private val deviceAbi: String =
        Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS } ?: Build.SUPPORTED_ABIS.first()
    private val _abi = MutableStateFlow(deviceAbi)
    val abi: StateFlow<String> = _abi.asStateFlow()
    private val _autoDetectedAbi = MutableStateFlow(deviceAbi)
    val autoDetectedAbi: StateFlow<String> = _autoDetectedAbi.asStateFlow()
    fun autoDetectAbi(): String {
        try {
            sourceManager.receivedFile()?.let { file ->
                val info = ZipAnalyzer.analyze(file)
                val supported = info.abis.filter { it in SUPPORTED_ABIS }
                if (supported.size == 1) return supported.first()
                val nameLower = file.name.lowercase()
                if (nameLower.contains("arm64") || nameLower.contains("aarch64") || nameLower.contains("v8a")) return "arm64-v8a"
                if (nameLower.contains("arm32") || nameLower.contains("v7a") || nameLower.contains("armeabi")) return "armeabi-v7a"
            }
        } catch (_: Throwable) {
        }
        try {
            val cachedSource = cachedClientApk()
            if (cachedSource != null && cachedSource.isFile) {
                val info = ZipAnalyzer.analyze(cachedSource)
                val supported = info.abis.filter { it in SUPPORTED_ABIS }
                if (supported.size == 1) return supported.first()
                val nameLower = cachedSource.name.lowercase()
                if (nameLower.contains("arm64") || nameLower.contains("aarch64") || nameLower.contains("v8a")) return "arm64-v8a"
                if (nameLower.contains("arm32") || nameLower.contains("v7a") || nameLower.contains("armeabi")) return "armeabi-v7a"
            }
        } catch (_: Throwable) {
        }
        val path = try { addonsManager.installPathValue() } catch (_: Throwable) { null }
        val detectedRoot = runCatching { addonsManager.resolveGameRoot() }.getOrNull()
        val gameDir = File(path ?: ((detectedRoot ?: "/storage/emulated/0/.xash") + "/cstrike"))
        val historyCandidates = buildList {
            add(File(gameDir, "patch-history.json"))
            if (detectedRoot != null) {
                add(File(detectedRoot, "cstrike/patch-history.json"))
                add(File(detectedRoot, "patch-history.json"))
            }
            add(File("/sdcard/xash/cstrike/patch-history.json"))
            add(File("/sdcard/cstrike/patch-history.json"))
            add(File("/sdcard/xash/patch-history.json"))
            add(File("/sdcard/.xash/cstrike/patch-history.json"))
        }
        for (historyFile in historyCandidates) {
            if (historyFile.isFile) {
                try {
                    val content = historyFile.readText()
                    if (content.contains("\"abi\":\"arm64-v8a\"")) return "arm64-v8a"
                    if (content.contains("\"abi\":\"armeabi-v7a\"")) return "armeabi-v7a"
                } catch (_: Throwable) {
                }
            }
        }
        val potentialLibFiles = buildList {
            add(File(gameDir, "cl_dlls/client.so"))
            add(File(gameDir, "dlls/cs.so"))
            add(File(gameDir, "libxash.so"))
            if (detectedRoot != null) {
                add(File(detectedRoot, "cstrike/cl_dlls/client.so"))
                add(File(detectedRoot, "cstrike/dlls/cs.so"))
                add(File(detectedRoot, "libxash.so"))
            }
            add(File("/sdcard/xash/cstrike/cl_dlls/client.so"))
            add(File("/sdcard/xash/libxash.so"))
            add(File("/sdcard/.xash/cstrike/cl_dlls/client.so"))
            add(File("/sdcard/.xash/cstrike/dlls/cs.so"))
        }
        for (libFile in potentialLibFiles) {
            if (libFile.isFile && libFile.length() >= 20) {
                try {
                    libFile.inputStream().use { stream ->
                        val header = ByteArray(20)
                        if (stream.read(header) == 20 &&
                            header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
                        ) {
                            val elfClass = header[4].toInt()
                            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                            if (elfClass == 2 || machine == 183) return "arm64-v8a"
                            if (elfClass == 1 || machine == 40) return "armeabi-v7a"
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
        try {
            val root = externalRoot
            val arm64Dir = File(root, "libs/arm64-v8a")
            val arm32Dir = File(root, "libs/armeabi-v7a")
            val arm64Count = arm64Dir.listFiles { f -> f.isFile && f.extension.equals("so", ignoreCase = true) }?.size ?: 0
            val arm32Count = arm32Dir.listFiles { f -> f.isFile && f.extension.equals("so", ignoreCase = true) }?.size ?: 0
            if (arm64Count > 0 && arm32Count == 0) return "arm64-v8a"
            if (arm32Count > 0 && arm64Count == 0) return "armeabi-v7a"
        } catch (_: Throwable) {
        }
        return deviceAbi
    }
    fun runAutoDetectAbi() {
        val detected = autoDetectAbi()
        _autoDetectedAbi.value = detected
        if (detected in SUPPORTED_ABIS && _abi.value in SUPPORTED_ABIS && detected != _abi.value) {
            setAbi(detected)
        }
    }
    val supportedAbis: List<String> = SUPPORTED_ABIS
    val patchableAbis: List<String> = SUPPORTED_ABIS

    val isDeviceAbi: String get() = deviceAbi
    val sourceAbis: List<String> get() = sourceManager.source.value?.abis ?: emptyList()
    val loadedBundleAbi: String? get() = bundleManager.loadedBundle?.manifest?.abi?.ifBlank { null }
    fun setAbi(abi: String) {
        if (abi !in SUPPORTED_ABIS || abi == _abi.value) return
        _abi.value = abi
        val b = bundleManager.loadedBundle
        if (b != null && b.manifest.abi.isNotBlank() && b.manifest.abi != abi) {
            bundleManager.clearLoadedBundle()
        }
        viewModelScope.launch(Dispatchers.IO) {
            applyGamedataAbiPolicy(File(addonsManager.installPathValue()), abi)
            if (bundleManager.loadedBundle == null) useCachedBundle()
            refreshAbiStatus()
        }
        scanLibs(autoLoad = true)
    }
    val addons: StateFlow<AddonsState> get() = addonsManager.addons
    data class AbiStatus(
        val abi: String,
        val inSource: Boolean,
        val libCount: Int,
        val hasBundle: Boolean,
        val selected: Boolean,
        val downloading: Boolean,
        val downloadPercent: Float,
    ) {
        val installed: Boolean get() = hasBundle && libCount > 0
    }
    val bundleVersion: StateFlow<String> get() = bundleManager.bundleVersion
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()
    private val _abiStatus = MutableStateFlow<List<AbiStatus>>(emptyList())
    val abiStatus: StateFlow<List<AbiStatus>> = _abiStatus.asStateFlow()
    private val downloadingAbi: String? get() = bundleManager.downloadingAbi
    fun refreshAbiStatus() {
        val sourceAbis = sourceManager.source.value?.abis ?: emptyList()
        val selected = _abi.value
        _abiStatus.value = supportedAbis.map { a ->
            val dir = File(libsDir, a)
            val count = if (dir.isDirectory) {
                dir.listFiles()?.count { it.isFile && it.name.endsWith(".so") } ?: 0
            } else 0
            AbiStatus(
                abi = a,
                inSource = sourceAbis.isEmpty() || a in sourceAbis,
                libCount = count,
                hasBundle = count > 0 || bundleProvider.hasCachedBundle(a) || bundleManager.loadedBundle?.manifest?.abi == a,
                selected = a == selected,
                downloading = downloadingAbi == a,
                downloadPercent = if (downloadingAbi == a) {
                    (bundleManager.bundle.value as? BundleState.Downloading)?.percent ?: 0f
                } else 0f,
            )
        }
    }
    private val sourceManager: SourceManager by lazy {
        SourceManager(
            app = app,
            scope = viewModelScope,
            externalRoot = externalRoot,
            deviceAbi = deviceAbi,
            abiProvider = { _abi.value },
            setAbi = { abi -> setAbi(abi) },
            runAutoDetectAbi = { runAutoDetectAbi() },
            refreshAbiStatus = { refreshAbiStatus() },
            failPatch = { msg -> patchManager.failPatch(msg) },
        )
    }
    private val _engineInstalled = MutableStateFlow(EngineProbe.isEngineInstalled(getApplication()))
    val engineInstalled: StateFlow<Boolean> = _engineInstalled.asStateFlow()
    private val _gameInstalled = MutableStateFlow(EngineProbe.isGameInstalled(getApplication()) && EngineProbe.gameDataPresent(getApplication()))
    val gameInstalled: StateFlow<Boolean> = _gameInstalled.asStateFlow()
    private val _cstrikeReady = MutableStateFlow(EngineProbe.cstrikePresent(getApplication()))
    val cstrikeReady: StateFlow<Boolean> = _cstrikeReady.asStateFlow()
    fun refreshGameInstalled() {
        _engineInstalled.value = EngineProbe.isEngineInstalled(getApplication())
        _gameInstalled.value = EngineProbe.isGameInstalled(getApplication()) && EngineProbe.gameDataPresent(getApplication())
        _cstrikeReady.value = EngineProbe.cstrikePresent(getApplication())
        refreshCleanupAvailability()
    }
    private val bundleManager: BundleManager by lazy {
        BundleManager(
            app = app,
            bundleProvider = bundleProvider,
            scope = viewModelScope,
            abiProvider = { _abi.value },
            appliedBuilds = { abi -> updateManager.appliedBuilds(abi) },
            recordAppliedBuilds = { abi, assets -> updateManager.recordAppliedBuilds(abi, assets) },
            markBundleTagKnown = { tag -> updateManager.markBundleTagKnown(tag) },
            refreshAbiStatus = { refreshAbiStatus() },
            setReleaseNote = { note -> _releaseNote.value = note },
            externalRoot = externalRoot(app),
            gameDirProvider = { File(addonsManager.installPathValue()) },
        )
    }
    private val libsDir = bundleManager.libsDir
    val libs: StateFlow<List<LibInfo>> get() = bundleManager.libs
    val customLibs: StateFlow<List<CustomLib>> get() = bundleManager.customLibs
    val libsChecking: StateFlow<Boolean> get() = bundleManager.libsChecking
    fun refreshLibStatus() = bundleManager.refreshLibStatus()
    private val updateManager: UpdateManager by lazy {
        UpdateManager(
            app = app,
            scope = viewModelScope,
            workFile = { name -> workFile(name) },
        )
    }
    private val addonsManager: AddonsManager by lazy {
        AddonsManager(
            app = app,
            scope = viewModelScope,
            bundleProvider = bundleProvider,
            repo = repo,
            abiProvider = { _abi.value },
            unzipInto = { zip, target -> patchManager.unzipInto(zip, target) },
            patchMetamodConfig = { gameDir, abi -> patchManager.patchMetamodConfig(gameDir, abi) },
            applyGamedataAbiPolicy = { gameDir, abi -> patchManager.applyGamedataAbiPolicy(gameDir, abi) },
        )
    }
    private val patchManager: PatchManager by lazy {
        PatchManager(
            app = app,
            scope = viewModelScope,
            bundleManager = bundleManager,
            bundleProvider = bundleProvider,
            repo = repo,
            sourceProvider = { sourceManager.receivedFile() },
            installPathProvider = { addonsManager.installPathValue() },
            abiProvider = { _abi.value },
            loadSigningKeystore = { loadSigningKeystore() },
            workFile = { name -> workFile(name) },
            installAddons = { addonsManager.fetchAndInstallAddons() },
        )
    }
    val patchWorkers: StateFlow<Int> get() = patchManager.patchWorkers
    fun maxPatchWorkers(): Int = patchManager.maxPatchWorkers()
    fun effectivePatchWorkers(): Int = patchManager.effectivePatchWorkers()
    fun setPatchWorkers(count: Int) = patchManager.setPatchWorkers(count)
    val patch: StateFlow<PatchUiState> get() = patchManager.patch
    val patchLog: StateFlow<List<String>> get() = patchManager.patchLog
        private val compilerManager = CompilerManager(
        app = app,
        bundleProvider = bundleProvider,
        scope = viewModelScope,
        abiProvider = { _abi.value },
        loadedBundleProvider = { bundleManager.loadedBundle },
        installPathProvider = { addonsManager.installPathValue() },
    )
    val scripts: StateFlow<List<SmaSource>> get() = compilerManager.scripts
    val scriptRoot: StateFlow<String?> get() = compilerManager.scriptRoot
    val outputRoot: StateFlow<String?> get() = compilerManager.outputRoot
    val compile: StateFlow<CompileState> get() = compilerManager.compile
    val compileWorkers: StateFlow<Int> get() = compilerManager.compileWorkers
    fun maxCompileWorkers(): Int = compilerManager.maxCompileWorkers()
    fun effectiveCompileWorkers(): Int = compilerManager.effectiveCompileWorkers()
    fun setCompileWorkers(count: Int) = compilerManager.setCompileWorkers(count)
    fun refreshScripts() {
        viewModelScope.launch(Dispatchers.IO) {
            compilerManager.autoFindAmxxFolder(false)
            compilerManager.autoFindAmxxFolder(true)
            compilerManager.refreshScripts()
        }
    }
    fun startScriptWatcher() = compilerManager.startScriptWatcher()
    fun stopScriptWatcher() = compilerManager.stopScriptWatcher()
    fun compile(source: SmaSource) = compilerManager.compile(source)
    fun compileAll(sources: List<SmaSource>) = compilerManager.compileAll(sources)
    fun addPluginSource(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val root = compilerManager.scriptRoot.value
            if (root == null) {
                compilerManager.failCompile(
                    "Plugin folder not detected yet. Install addons first, then try again.",
                )
                return@launch
            }
            val resolver = getApplication<Application>().contentResolver
            val name = runCatching {
                resolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
            }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "plugin-${System.currentTimeMillis()}.sma"
            if (!name.endsWith(".sma", ignoreCase = true)) {
                compilerManager.failCompile("The picked file is not a .sma file: $name")
                return@launch
            }
            val dest = File(File(root), name)
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            }.isSuccess
            if (!copied) {
                compilerManager.failCompile("Could not copy $name to $root")
                return@launch
            }
            compilerManager.autoFindAmxxFolder(true)
            compilerManager.refreshScripts()
            val includeDir = File(File(root), "include").isDirectory()
            compilerManager.compile(SmaSource(dest.absolutePath, name, includeDir))
        }
    }
    val hasCachedBundle: Boolean get() = bundleManager.hasCachedBundle
    val repo = "Yoriva/patcher"
private val externalRoot: File = externalRoot(app)
private fun externalRoot(app: Application): File {
        val base = app.getExternalFilesDir(null) ?: app.cacheDir
        val newRoot = File(base, YORIVA_ROOT)
        com.yoriva.data.migrateLegacyYorivaRoot(File(Environment.getExternalStorageDirectory(), YORIVA_ROOT), newRoot)
        newRoot.mkdirs()
        return newRoot
}
    private val workDir = File(externalRoot, "patcher")
    private fun workFile(name: String): File {
        workDir.mkdirs()
        return File(workDir, name)
    }
    init {
        scanLibs()
        useCachedBundle()
        scanCustomLibs()
        runAutoDetectAbi()
    }
    private val _releaseNote = MutableStateFlow<String?>(null)
    val releaseNote: StateFlow<String?> = _releaseNote.asStateFlow()
    private fun loadSigningKeystore(): SigningKeystore {
        val assets = getApplication<Application>().assets
        val keyPem = runCatching {
            assets.open("keystore/debug_key.pem").use { it.readBytes().decodeToString() }
        }.getOrNull()
        val certPem = runCatching {
            assets.open("keystore/debug_cert.pem").use { it.readBytes().decodeToString() }
        }.getOrNull()
        return if (keyPem != null && certPem != null) {
            SigningKeystore.loadPem(keyPem, certPem)
        } else {
            val p12 = assets.open("keystore/debug.p12").use { it.readBytes() }
            SigningKeystore.loadBytes(p12)
        }
    }
    private fun cachedClientApk(): File? = sourceManager.cachedClientApk()
    fun refreshSourceApkStatus() = sourceManager.refreshSourceApkStatus()
    fun downloadSourceApk() = sourceManager.downloadSourceApk()
    fun useCachedBundle() = bundleManager.useCachedBundle()
    fun useLoadedBundle() = bundleManager.useLoadedBundle()
    fun scanLibs(autoLoad: Boolean = false, checkRemote: Boolean = false) = bundleManager.scanLibs(autoLoad, checkRemote)
    fun refreshSingleLib(libName: String) = bundleManager.refreshSingleLib(libName)
    fun fetchAndDownloadBundle() = bundleManager.fetchAndDownloadBundle()
    val libExcluded: StateFlow<Set<String>> get() = bundleManager.libExcluded
    fun toggleLibExcluded(name: String) = bundleManager.toggleLibExcluded(name)
    fun setLibExcluded(name: String, excluded: Boolean) = bundleManager.setLibExcluded(name, excluded)
    private var downloadAllJob: Job? = null
    private val _downloadAll = MutableStateFlow(false)
    val downloadAllRunning: StateFlow<Boolean> = _downloadAll.asStateFlow()
    fun downloadAll() {
        if (downloadAllJob?.isActive == true) return
        _downloadAll.value = true
        downloadAllJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                sourceManager.clearSourceCache()
                bundleManager.clearLocalLibraryCache()
                updateManager.clearLibraryState()
                sourceManager.downloadSourceApkNow()
                bundleManager.fetchAndDownloadBundleNow()
            } finally {
                _downloadAll.value = false
            }
        }
    }
    data class PatchComponent(
        val key: String,
        val label: String,
        val description: String,
        val entryTargets: List<String>,
    ) {
        override fun equals(other: Any?): Boolean = other is PatchComponent && other.key == key
        override fun hashCode(): Int = key.hashCode()
    }
    data class CustomLib(
        val name: String,
        val file: File,
        val machineName: String,
        val shadows: String?,
    ) {
        override fun equals(other: Any?): Boolean = other is CustomLib && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }
    fun addCustomLibs(uris: List<android.net.Uri>) = bundleManager.addCustomLibs(uris)
    val customLibProblem: StateFlow<String?> get() = bundleManager.customLibProblem
    fun dismissCustomLibProblem() = bundleManager.dismissCustomLibProblem()
    fun removeCustomLib(name: String) = bundleManager.removeCustomLib(name)
    private fun scanCustomLibs() = bundleManager.scanCustomLibs()
    fun customLibEntries(bundle: Bundle, selected: Set<String>) = bundleManager.customLibEntries(bundle, selected)
    fun staleCustomLibs() = bundleManager.staleCustomLibs()
    fun patchComponents() = bundleManager.patchComponents()
    fun fetchAndInstallAddons() = addonsManager.fetchAndInstallAddons()
    private fun unzipInto(zip: File, target: File): Int = patchManager.unzipInto(zip, target)
    private fun patchMetamodConfig(gameDir: File, abi: String) = patchManager.patchMetamodConfig(gameDir, abi)
    private fun applyGamedataAbiPolicy(gameDir: File, abi: String): Boolean = patchManager.applyGamedataAbiPolicy(gameDir, abi)
    val pendingInstall: StateFlow<Intent?> get() = patchManager.pendingInstall
    val pendingUninstall: StateFlow<Intent?> get() = patchManager.pendingUninstall
    fun consumePendingInstall() = patchManager.consumePendingInstall()
    fun consumePendingUninstall() = patchManager.consumePendingUninstall()
    fun startPatch(selectedComponentKeys: Set<String>? = null) = patchManager.startPatch(selectedComponentKeys)
    fun requestInstall() = patchManager.requestInstall()
    fun onUninstallFinished() = patchManager.onUninstallFinished()
    fun installIntentFor(apk: File): Intent? = patchManager.installIntentFor(apk)
    fun reset() = patchManager.reset()
    val appUpdate: StateFlow<AppUpdate> get() = updateManager.appUpdate
    fun checkAppUpdate() = updateManager.checkAppUpdate()
    fun downloadAppUpdate() = updateManager.downloadAppUpdate()
    fun dismissUpdate() = updateManager.dismissUpdate()
    fun consumeDownloaded() = updateManager.consumeDownloaded()
    fun markBundleTagKnown(tag: String) = updateManager.markBundleTagKnown(tag)
    private val pluginIniManager = PluginIniManager(
        app = app,
        scope = viewModelScope,
        installPathProvider = { addonsManager.installPathValue() },
    )
    val pluginInis: StateFlow<List<ConfigFileInfo>> get() = pluginIniManager.pluginInis
    val iniText: StateFlow<String> get() = pluginIniManager.iniText
    val iniDirty: StateFlow<Boolean> get() = pluginIniManager.iniDirty
    val iniSavedAt: StateFlow<Long> get() = pluginIniManager.iniSavedAt
    fun openPluginIni(file: File) = pluginIniManager.openPluginIni(file)
    fun editIniText(text: String) = pluginIniManager.editIniText(text)
    fun savePluginIni(file: File) = pluginIniManager.savePluginIni(file)
    fun revertPluginIni(file: File) = pluginIniManager.revertPluginIni(file)
    fun loadPluginInis() = pluginIniManager.loadPluginInis()
    suspend fun buildLogShareText(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val pm = getApplication<Application>().packageManager
        val pkg = getApplication<Application>().packageName
        val ver = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).versionName
            }
        } catch (_: Throwable) {
            "unknown"
        }
        sb.append("Yoriva ").append(ver).append("\n")
        sb.append("Game dir: ").append(addonsManager.installPathValue()).append("\n\n")
        fun tail(f: File, max: Int = 60): List<String> = try {
            if (!f.exists()) return listOf("(missing: ${f.path})")
            val lines = f.readLines()
            lines.takeLast(max)
        } catch (t: Throwable) {
            listOf("(unreadable: ${t.message})")
        }
        val base = File(addonsManager.installPathValue())
        sb.append("=== crash.log ===\n")
        tail(File(base, "crash.log"), 40).forEach { sb.append(it).append("\n") }
        sb.append("\n=== engine.log (tail) ===\n")
        tail(File(File(base, "").parent ?: "", "engine.log"), 40).forEach { sb.append(it).append("\n") }
        val logDir = File(base, "addons/amxmodx/logs")
        val latest = logDir.listFiles { f -> f.isFile && f.name.startsWith("L") }
            ?.maxByOrNull { it.lastModified() }
        if (latest != null) {
            sb.append("\n=== ${latest.name} (tail) ===\n")
            tail(latest, 60).forEach { sb.append(it).append("\n") }
        }
        sb.toString()
    }
    companion object {
        const val JOB_CLIENT_APK = "client-apk"
        const val JOB_LIBS = "libs"
        const val JOB_ADDONS = "addons"
        const val JOB_PATCH = "patch"
        const val GAME_CSTRIKE = "cstrike"
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")
        const val ASSET_DATE_SLOP = 5 * 60 * 1000L
        const val GAMEDATA_DISABLED_SUFFIX = ".disabled"
        val GAMEDATA_VARIANT = Regex("offsets-cstrike-replugged\\.(arm64|arm32)\\.txt")
        const val LEGACY_GAMEDATA_NAME = "offsets-cstrike-replugged.txt"
        const val APP_RELEASE_REPO = "Yoriva/patcher"
        const val CLIENT_APK_REPO = APP_RELEASE_REPO
        const val CLIENT_APK_ASSET = "CS16Client-Android.apk"
        const val GAME_CLIENT_PACKAGE = "su.xash.cs16client.nexora"
        const val CONTINUOUS_TAG = "continuous"
        const val CONTINUOUS_APK = "yoriva_continuous.apk"
        const val RELEASE_APK = "yoriva.apk"
        const val VERSION_FILE = "version.txt"
        val PROTECTED_ADDON_CONFIGS = setOf(
            "addons/amxmodx/configs/modules.ini",
            "addons/amxmodx/configs/amxx.cfg",
            "addons/amxmodx/configs/cvars.ini",
            "addons/amxmodx/configs/maps.ini",
            "addons/amxmodx/configs/plugins.ini",
            "addons/amxmodx/configs/sql.cfg",
            "addons/amxmodx/configs/users.ini",
        )
    }
    fun autoInstallAddons() = addonsManager.autoInstallAddons()
    private val _cleanup = MutableStateFlow<CleanupState>(CleanupState.Idle)
    val cleanup: StateFlow<CleanupState> = _cleanup.asStateFlow()
    private val _cleanupAvailable = MutableStateFlow(false)
    val cleanupAvailable: StateFlow<Boolean> = _cleanupAvailable.asStateFlow()
    private fun cleanupTargets(gameDir: File): List<File> = listOf(
        workDir,
        File(externalRoot, "libs"),
        File(externalRoot, "apk-source"),
        File(externalRoot, "downloads"),
        File(app.filesDir, "compiler"),
        File(app.filesDir, "apk-source"),
        File(app.filesDir, "custom-libs"),
        File(gameDir, "addons"),
        File(gameDir, "dlls"),
        File(gameDir, "cl_dlls"),
        File(gameDir, "patch-history.json"),
    )
    private fun hasCleanupTargets(): Boolean {
        if (EngineProbe.isClientInstalled(app)) return true
        val gameDir = File(addonsManager.installPathValue())
        return cleanupTargets(gameDir).any { target ->
            target.isFile || (target.isDirectory && target.listFiles()?.isNotEmpty() == true)
        }
    }
    fun refreshCleanupAvailability() {
        viewModelScope.launch(Dispatchers.IO) {
            _cleanupAvailable.value = hasCleanupTargets()
        }
    }
    fun cleanAll() {
        if (_cleanup.value is CleanupState.Running) return
        if (!hasCleanupTargets()) {
            _cleanupAvailable.value = false
            _cleanup.value = CleanupState.Done(0)
            return
        }
        _cleanup.value = CleanupState.Running
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gameDir = File(addonsManager.installPathValue())
                val targets = cleanupTargets(gameDir)
                var removed = 0
                targets.forEach { target ->
                    if (!target.exists()) return@forEach
                    val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
                    if (ok) removed++
                }
                bundleManager.resetAll()
                addonsManager.reset()
                patchManager.reset()
                listOf(JOB_PATCH, JOB_ADDONS, JOB_LIBS, JOB_CLIENT_APK).forEach {
                    com.yoriva.jobs.JobProgress.clear(it)
                }
                scanCustomLibs()
                scanLibs()
                refreshAbiStatus()
                refreshGameInstalled()
                patchManager.requestCleanupUninstall()
                _cleanupAvailable.value = false
                _cleanup.value = CleanupState.Done(removed)
            } catch (t: Throwable) {
                _cleanupAvailable.value = hasCleanupTargets()
                _cleanup.value = CleanupState.Failed(t.message ?: "Cleanup failed")
            }
        }
    }
    fun dismissCleanup() {
        _cleanup.value = CleanupState.Idle
    }
    val installPath: StateFlow<String> get() = addonsManager.installPath
    val addonsInstalled: StateFlow<Boolean> get() = addonsManager.addonsInstalled
}
