package com.pickle.patcher.patcher
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.IncrementalUpdateManager
import com.pickle.patcher.data.NEXORA_ROOT
import com.pickle.patcher.data.ReleaseRepository
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.SigningKeystore
import com.pickle.patcher.lib.ZipAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import com.pickle.patcher.jobs.JobProgress
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
data class LibInfo(
    val name: String,
    val localSize: Long,
    val releaseSize: Long,
    val upToDate: Boolean,
    val downloading: Boolean = false,
    val downloadProgress: Float = -1f,
)
class PatcherViewModel(app: Application) : AndroidViewModel(app) {
    private val bundleProvider = BundleProvider(app)
    private val app: Application = app
    private val _source = MutableStateFlow<SourceInfo?>(null)
    val source: StateFlow<SourceInfo?> = _source.asStateFlow()
    private val _receivedSource: MutableStateFlow<File?> = MutableStateFlow(null)
    private val _sourceDownload = MutableStateFlow<SourceDownloadState?>(null)
    val sourceDownload: StateFlow<SourceDownloadState?> = _sourceDownload.asStateFlow()
    val bundle: StateFlow<BundleState> get() = bundleManager.bundle
    private val deviceAbi: String =
        Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS } ?: SUPPORTED_ABIS.first()
    private val _abi = MutableStateFlow(deviceAbi)
    val abi: StateFlow<String> = _abi.asStateFlow()
    private val _autoDetectedAbi = MutableStateFlow(deviceAbi)
    val autoDetectedAbi: StateFlow<String> = _autoDetectedAbi.asStateFlow()
    fun autoDetectAbi(): String {
        try {
            _receivedSource.value?.let { file ->
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
        val path = try { _installPath.value } catch (_: Throwable) { null }
        val detectedRoot = runCatching { resolveGameRoot() }.getOrNull()
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
        if (detected in SUPPORTED_ABIS && detected != _abi.value) {
            setAbi(detected)
        }
    }
    val supportedAbis: List<String> = SUPPORTED_ABIS
    val isDeviceAbi: String get() = deviceAbi
    val sourceAbis: List<String> get() = _source.value?.abis ?: emptyList()
    val loadedBundleAbi: String? get() = bundleManager.loadedBundle?.manifest?.abi?.ifBlank { null }
    private fun resolveAbi(sourceAbis: List<String>): String? = when {
        sourceAbis.isEmpty() -> null
        deviceAbi in sourceAbis -> deviceAbi
        else -> sourceAbis.first()
    }
    fun setAbi(abi: String) {
        if (abi !in SUPPORTED_ABIS || abi == _abi.value) return
        _abi.value = abi
        val b = bundleManager.loadedBundle
        if (b != null && b.manifest.abi.isNotBlank() && b.manifest.abi != abi) {
            bundleManager.clearLoadedBundle()
        }
        viewModelScope.launch(Dispatchers.IO) {
            applyGamedataAbiPolicy(File(_installPath.value), abi)
            if (bundleManager.loadedBundle == null) useCachedBundle()
            refreshAbiStatus()
        }
        scanLibs(autoLoad = true)
    }
    private val _addons = MutableStateFlow<AddonsState>(AddonsState.None)
    val addons: StateFlow<AddonsState> = _addons.asStateFlow()
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
    data class PatchHistoryEntry(
        val time: Long,
        val abi: String,
        val source: String,
        val entries: Int,
        val libs: Int,
        val verified: Boolean,
        val ok: Boolean,
    )
    data class PatchSummary(
        val abi: String,
        val source: String,
        val sourceSize: Long,
        val components: Int,
        val entries: Int,
        val payloadSize: Long,
    )
    sealed interface PatchCheck {
        data object Idle : PatchCheck
        data object Running : PatchCheck
        data class Result(
            val signatureOk: Boolean,
            val usedV1: Boolean,
            val usedV2: Boolean,
            val expectedLibs: Int,
            val presentLibs: Int,
            val engineCommit: String,
        ) : PatchCheck
        data class Failed(val message: String) : PatchCheck
    }
    val bundleVersion: StateFlow<String> get() = bundleManager.bundleVersion
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()
    fun shouldReplayIntro(version: String): Boolean {
        if (version.isBlank()) return false
        if (bundleManager.bundleVersion.value != version) {
            bundleManager.setBundleVersion(version)
            val prefs = getApplication<Application>()
                .getSharedPreferences("patcher_ui", Context.MODE_PRIVATE)
            val seen = prefs.getString("intro_version", null)
            if (seen != version) {
                prefs.edit().putString("intro_version", version).apply()
                return true
            }
        }
        return false
    }
    private val _abiStatus = MutableStateFlow<List<AbiStatus>>(emptyList())
    val abiStatus: StateFlow<List<AbiStatus>> = _abiStatus.asStateFlow()
    private val downloadingAbi: String? get() = bundleManager.downloadingAbi
    fun refreshAbiStatus() {
        val sourceAbis = _source.value?.abis ?: emptyList()
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
    private val bundleManager by lazy {
        BundleManager(
            app = app,
            bundleProvider = bundleProvider,
            scope = viewModelScope,
            abiProvider = { _abi.value },
            appliedBuilds = { abi -> appliedBuilds(abi) },
            recordAppliedBuilds = { abi, assets -> recordAppliedBuilds(abi, assets) },
            markBundleTagKnown = { tag -> markBundleTagKnown(tag) },
            refreshAbiStatus = { refreshAbiStatus() },
            setReleaseNote = { note -> _releaseNote.value = note },
            externalRoot = externalRoot(app),
        )
    }
    private val libsDir = bundleManager.libsDir
    val libs: StateFlow<List<LibInfo>> get() = bundleManager.libs
    val customLibs: StateFlow<List<CustomLib>> get() = bundleManager.customLibs
    val libsChecking: StateFlow<Boolean> get() = bundleManager.libsChecking
    fun refreshLibStatus() = bundleManager.refreshLibStatus()
    private val patchManager by lazy {
        PatchManager(
            app = app,
            scope = viewModelScope,
            bundleManager = bundleManager,
            bundleProvider = bundleProvider,
            repo = repo,
            sourceProvider = { _receivedSource.value },
            installPathProvider = { _installPath.value },
            abiProvider = { _abi.value },
            loadSigningKeystore = { loadSigningKeystore() },
            workFile = { name -> workFile(name) },
        )
    }
    val patchWorkers: StateFlow<Int> get() = patchManager.patchWorkers
    fun maxPatchWorkers(): Int = patchManager.maxPatchWorkers()
    fun effectivePatchWorkers(): Int = patchManager.effectivePatchWorkers()
    fun setPatchWorkers(count: Int) = patchManager.setPatchWorkers(count)
    val patch: StateFlow<PatchUiState> get() = patchManager.patch
    val patchLog: StateFlow<List<String>> get() = patchManager.patchLog
    val patchHistory: StateFlow<List<PatchHistoryEntry>> get() = patchManager.patchHistory
    val patchCheck: StateFlow<PatchCheck> get() = patchManager.patchCheck
        private val compilerManager = CompilerManager(
        app = app,
        bundleProvider = bundleProvider,
        scope = viewModelScope,
        abiProvider = { _abi.value },
        loadedBundleProvider = { bundleManager.loadedBundle },
        installPathProvider = { _installPath.value },
    )
    val scripts: StateFlow<List<SmaSource>> get() = compilerManager.scripts
    val scriptRoot: StateFlow<String?> get() = compilerManager.scriptRoot
    val outputRoot: StateFlow<String?> get() = compilerManager.outputRoot
    val compile: StateFlow<CompileState> get() = compilerManager.compile
    val compileWorkers: StateFlow<Int> get() = compilerManager.compileWorkers
    fun maxCompileWorkers(): Int = compilerManager.maxCompileWorkers()
    fun effectiveCompileWorkers(): Int = compilerManager.effectiveCompileWorkers()
    fun setCompileWorkers(count: Int) = compilerManager.setCompileWorkers(count)
    fun refreshScripts() = compilerManager.refreshScripts()
    fun startScriptWatcher() = compilerManager.startScriptWatcher()
    fun stopScriptWatcher() = compilerManager.stopScriptWatcher()
    fun setScriptRoot(uri: Uri?) = compilerManager.setScriptRoot(uri)
    fun setOutputRoot(uri: Uri?) = compilerManager.setOutputRoot(uri)
    fun clearOutputRoot() = compilerManager.clearOutputRoot()
    fun clearScriptRoot() = compilerManager.clearScriptRoot()
    fun autoFindAmxxFolder(forOutput: Boolean): Boolean = compilerManager.autoFindAmxxFolder(forOutput)
    fun autoFindTargetName(forOutput: Boolean): String = compilerManager.autoFindTargetName(forOutput)
    fun compile(source: SmaSource) = compilerManager.compile(source)
    fun compileAll(sources: List<SmaSource>) = compilerManager.compileAll(sources)
    val hasCachedBundle: Boolean get() = bundleManager.hasCachedBundle
    val repo = "berkchy/nexora"
private val externalRoot: File = externalRoot(app)
private fun externalRoot(app: Application): File {
        val sd = File(Environment.getExternalStorageDirectory(), NEXORA_ROOT)
        return if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        ) {
            sd
        } else {
            app.getExternalFilesDir(null) ?: app.cacheDir
        }
    }
    private val workDir = File(externalRoot, "patcher")
    private val sourceCacheDir = File(externalRoot, "apk-source")
    private val legacySourceDir = File(getApplication<Application>().filesDir, "apk-source")
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
    private fun cachedClientApk(): File? {
        val candidates = listOfNotNull(sourceCacheDir, legacySourceDir).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
                ?.toList()
                ?: emptyList()
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name == CLIENT_APK_ASSET }
            ?: candidates.maxByOrNull { it.lastModified() }
    }
    private fun apkProblem(file: File): String? = try {
        val info = ZipAnalyzer.analyze(file)
        if (info.abis.isNotEmpty() && info.abis.none { it in SUPPORTED_ABIS }) {
            "no supported native ABI (" + info.abis.joinToString(", ") + ")"
        } else {
            null
        }
    } catch (t: Throwable) {
        t.message?.takeIf { it.isNotBlank() } ?: "unreadable archive"
    }
    private fun useSourceFile(file: File, label: String) {
        val info = ZipAnalyzer.analyze(file)
        val supported = info.abis.filter { it in SUPPORTED_ABIS }
        if (info.abis.isNotEmpty() && supported.isEmpty()) {
            patchManager.failPatch(
                "This APK has no supported native ABIs (found: " +
                    "${info.abis.joinToString(", ")}). " +
                    "Supported: ${SUPPORTED_ABIS.joinToString(", ")}."
            )
            _receivedSource.value = null
            return
        }
        if (supported.isNotEmpty()) {
            if (supported.size == 1) {
                setAbi(supported.first())
            } else if (_abi.value !in supported) {
                resolveAbi(supported)?.let { setAbi(it) }
            }
        } else {
            runAutoDetectAbi()
        }
        _source.value = SourceInfo(label, file.length(), info.entryCount, supported)
        _receivedSource.value = file
        refreshAbiStatus()
    }
    fun refreshSourceApkStatus() {
        if (_sourceDownload.value is SourceDownloadState.Fetching ||
            _sourceDownload.value is SourceDownloadState.Downloading
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val cached = cachedClientApk()
            if (cached == null) {
                _sourceDownload.value = null
                return@launch
            }
            val tag = "extras"
            val remote = tag?.let {
                ReleaseRepository.probe(ReleaseRepository.assetUrl(CLIENT_APK_REPO, it, CLIENT_APK_ASSET))
            }
            val recorded = cached?.let { readSourceStamp(it) }
            val problem = cached?.let { apkProblem(it) }
            val outdated = if (cached != null && remote != null) {
                outdatedReasons(cached, remote, recorded)
            } else {
                emptyList()
            }
            _sourceDownload.value = when {
                problem != null -> SourceDownloadState.Corrupt(CLIENT_APK_ASSET, problem)
                outdated.isNotEmpty() && cached != null -> SourceDownloadState.Outdated(
                    CLIENT_APK_ASSET,
                    cached.length(),
                    remote?.size ?: 0L,
                    outdated.joinToString(", "),
                )
                else -> SourceDownloadState.Done(CLIENT_APK_ASSET)
            }
            if (_sourceDownload.value is SourceDownloadState.Done &&
                cached != null &&
                _receivedSource.value?.absolutePath != cached.absolutePath &&
                tag != null
            ) {
                useSourceFile(cached, "$cached.name ($tag)")
            }
        }
    }
    private fun outdatedReasons(
        cached: File,
        remote: ReleaseRepository.RemoteInfo,
        recorded: SourceStamp?,
    ): List<String> {
        val reasons = mutableListOf<String>()
        val localSize = cached.length()
        if (recorded != null) {
            if (recorded.size != localSize) reasons += "size changed since download"
            if (remote.etag.isNotEmpty() && recorded.etag.isNotEmpty() &&
                remote.etag != recorded.etag
            ) {
                reasons += "newer build on GitHub"
            }
            if (remote.lastModifiedMillis > 0L && recorded.lastModifiedMillis > 0L &&
                remote.lastModifiedMillis != recorded.lastModifiedMillis
            ) {
                reasons += "rebuilt since download"
            }
        } else {
            if (remote.size > 0L && localSize != remote.size) {
                reasons += "size differs from GitHub"
            }
            if (remote.lastModifiedMillis > 0L &&
                cached.lastModified() + ASSET_DATE_SLOP < remote.lastModifiedMillis
            ) {
                reasons += "older than the GitHub build"
            }
        }
        return reasons
    }
    private data class SourceStamp(val size: Long, val etag: String, val lastModifiedMillis: Long)
    private fun readSourceStamp(cached: File): SourceStamp? {
        val file = File(cached.parentFile ?: return null, "${cached.name}.stamp")
        if (!file.isFile) return null
        return try {
            val parts = file.readText().trim().split(' ')
            if (parts.size < 3) {
                null
            } else {
                SourceStamp(parts[0].toLong(), parts[1], parts[2].toLong())
            }
        } catch (_: Throwable) {
            null
        }
    }
    private fun writeSourceStamp(cached: File, remote: ReleaseRepository.RemoteInfo?) {
        try {
            cached.parentFile?.mkdirs()
            File(cached.parentFile, "${cached.name}.stamp").writeText(
                "${cached.length()} ${remote?.etag.orEmpty()} ${remote?.lastModifiedMillis ?: 0L}"
            )
        } catch (_: Throwable) {
        }
    }
    fun downloadSourceApk() {
        if (_sourceDownload.value is SourceDownloadState.Fetching ||
            _sourceDownload.value is SourceDownloadState.Downloading
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _sourceDownload.value = SourceDownloadState.Fetching
            JobProgress.begin(app, JOB_CLIENT_APK, "Client APK", "Finding the release…")
            var partial: File? = null
            try {
                val repo = CLIENT_APK_REPO
                val tag = "extras"
                val name = CLIENT_APK_ASSET
                val url = ReleaseRepository.assetUrl(repo, tag, name)
                val remote = ReleaseRepository.probe(url)
                val total = remote?.size ?: 0L
                val dir = sourceCacheDir.apply { mkdirs() }
                val dest = File(dir, name)
                val part = File(dir, "$name.part").also { it.delete() }
                partial = part
                ReleaseRepository.downloadUrl(url, part, total) { done, size ->
                    _sourceDownload.value = SourceDownloadState.Downloading(done, size)
                    JobProgress.progress(app, JOB_CLIENT_APK, done, size, "Downloading…")
                }
                if (total > 0 && part.length() != total) {
                    part.delete()
                    throw IOException(
                        "Download stopped at %.1f MB of %.1f MB".format(java.util.Locale.US,
                            part.length() / 1048576.0,
                            total / 1048576.0,
                        )
                    )
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                apkProblem(dest)?.let {
                    dest.delete()
                    throw IOException("The downloaded APK is broken: $it")
                }
                writeSourceStamp(dest, remote)
                useSourceFile(dest, "$name ($tag)")
                _sourceDownload.value = SourceDownloadState.Done(name)
                JobProgress.finish(app, JOB_CLIENT_APK, "Client APK ready", apkPath = dest.path)
            } catch (t: Throwable) {
                partial?.delete()
                _sourceDownload.value = SourceDownloadState.Failed(t.message ?: "Download failed")
                JobProgress.finish(
                    app,
                    JOB_CLIENT_APK,
                    t.message ?: "Download failed",
                    ok = false,
                )
            }
        }
    }
    fun clearSourceDownloadState() {
        _sourceDownload.value = null
    }
    fun useCachedBundle() = bundleManager.useCachedBundle()
    fun useLoadedBundle() = bundleManager.useLoadedBundle()
    fun scanLibs(autoLoad: Boolean = false, checkRemote: Boolean = false) = bundleManager.scanLibs(autoLoad, checkRemote)
    fun refreshSingleLib(libName: String) = bundleManager.refreshSingleLib(libName)
    fun fetchAndDownloadBundle() = bundleManager.fetchAndDownloadBundle()
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
    fun fetchAndInstallAddons() {
        viewModelScope.launch(Dispatchers.IO) {
            _addons.value = AddonsState.Downloading(0f, "Resolving latest release…")
            JobProgress.begin(app, JOB_ADDONS, "Addons", "Resolving the release…")
            try {
                val tag = "extras"
                val zip = File(bundleProvider.cacheDir(), "amxx-addons.zip")
                _addons.value = AddonsState.Downloading(0f, "Downloading addons…")
                ReleaseRepository.downloadUrl(
                    ReleaseRepository.assetUrl(repo, tag, "amxx-addons.zip"),
                    zip,
                ) { done, total ->
                    _addons.value = AddonsState.Downloading(
                        if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
                        "Downloading addons…"
                    )
                    JobProgress.progress(app, JOB_ADDONS, done, total, "Downloading…")
                }
                _addons.value = AddonsState.Downloading(1f, "Extracting into ${_installPath.value.substringAfterLast("/")}…")
                val target = File(_installPath.value)
                val count = unzipInto(zip, target)
                patchMetamodConfig(target, _abi.value)
                val gamedataToggled = applyGamedataAbiPolicy(target, _abi.value)
                JobProgress.finish(app, JOB_ADDONS, "Addons installed")
                _addons.value = AddonsState.Done(
                    buildString {
                        append("Installed $count addons files into ${target.path}")
                        if (gamedataToggled) {
                            val variant = if (_abi.value == "arm64-v8a") "arm64" else "arm32"
                            append("\n$variant gamedata override selected for ${_abi.value}")
                        }
                    }
                )
                scanAddonsStatus()
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
                JobProgress.finish(app, JOB_ADDONS, t.message ?: "Install failed", ok = false)
            }
        }
    }
    fun installAddonsFromBundle() {
        fetchAndInstallAddons()
    }
    private fun unzipInto(zip: File, target: File): Int = patchManager.unzipInto(zip, target)
    private fun patchMetamodConfig(gameDir: File, abi: String) = patchManager.patchMetamodConfig(gameDir, abi)
    private fun applyGamedataAbiPolicy(gameDir: File, abi: String): Boolean = patchManager.applyGamedataAbiPolicy(gameDir, abi)
    val replacePrompt: StateFlow<File?> get() = patchManager.replacePrompt
    val uninstallPrompt: StateFlow<List<String>> get() = patchManager.uninstallPrompt
    val pendingInstall: StateFlow<Intent?> get() = patchManager.pendingInstall
    val pendingUninstall: StateFlow<Intent?> get() = patchManager.pendingUninstall
    fun confirmReplaceAndPatch(selectedComponentKeys: Set<String>? = null) = patchManager.confirmReplaceAndPatch(selectedComponentKeys)
    fun dismissReplacePrompt() = patchManager.dismissReplacePrompt()
    fun dismissUninstallPrompt() = patchManager.dismissUninstallPrompt()
    fun consumePendingInstall() = patchManager.consumePendingInstall()
    fun consumePendingUninstall() = patchManager.consumePendingUninstall()
    fun startPatch(selectedComponentKeys: Set<String>? = null) = patchManager.startPatch(selectedComponentKeys)
    fun outputApk(): File? = patchManager.outputApk()
    fun installedClientPackages(): List<String> = patchManager.installedClientPackages()
    fun requestInstall() = patchManager.requestInstall()
    fun onUninstallFinished() = patchManager.onUninstallFinished()
    fun confirmUninstallAndPatch() = patchManager.confirmUninstallAndPatch()
    fun installIntent(): Intent? = patchManager.installIntent()
    fun installIntentFor(apk: File): Intent? = patchManager.installIntentFor(apk)
    fun reset() = patchManager.reset()
    fun patchSummary(): PatchSummary? = patchManager.patchSummary()
    fun verifyPatchedApk() = patchManager.verifyPatchedApk()
    sealed interface AppUpdate {
        data object Idle : AppUpdate
        data object Checking : AppUpdate
        data class Available(
            val tag: String,
            val notes: String,
            val commits: List<String>,
            val size: Long,
            val url: String,
        ) : AppUpdate
        data class Downloading(
            val tag: String,
            val downloaded: Long,
            val total: Long,
            val bytesPerSec: Long,
        ) : AppUpdate
        data class Downloaded(val tag: String, val file: File) : AppUpdate
        data class UpToDate(val tag: String) : AppUpdate
        data class Failed(val message: String) : AppUpdate
    }
    private val _appUpdate = MutableStateFlow<AppUpdate>(AppUpdate.Idle)
    val appUpdate: StateFlow<AppUpdate> = _appUpdate.asStateFlow()
    private val updatePrefs by lazy {
        getApplication<Application>().getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }
    fun checkAppUpdate() {
        val cur = _appUpdate.value
        if (cur is AppUpdate.Checking || cur is AppUpdate.Downloading || cur is AppUpdate.Downloaded) return
        viewModelScope.launch(Dispatchers.IO) {
            _appUpdate.value = AppUpdate.Checking
            try {
                val latest = ReleaseRepository.latestReleaseTag(APP_RELEASE_REPO)
                val (tag, apkName) = if (latest != null) {
                    latest to RELEASE_APK
                } else {
                    CONTINUOUS_TAG to CONTINUOUS_APK
                }
                val remoteVersion = ReleaseRepository.fetchText(
                    ReleaseRepository.assetUrl(APP_RELEASE_REPO, tag, VERSION_FILE)
                ) ?: throw IOException("Could not reach GitHub releases")
                val apkUrl = ReleaseRepository.assetUrl(APP_RELEASE_REPO, tag, apkName)
                val apkSize = ReleaseRepository.probeSize(apkUrl)
                val app = getApplication<Application>()
                val ours = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Throwable) {
                    null
                }
                val localSize = try {
                    File(app.applicationInfo.sourceDir).length().takeIf { it > 0 }
                } catch (_: Throwable) {
                    null
                }
                val versionDiffers = ours == null || remoteVersion != ours
                val sizeDiffers = apkSize != null && apkSize > 0 &&
                    localSize != null && apkSize != localSize
                val notes = buildString {
                    if (ours != null) append("Installed: $ours")
                    if (localSize != null) append(" (${mb(localSize)})")
                    append("  →  $remoteVersion")
                    if (apkSize != null && apkSize > 0) append(" (${mb(apkSize)})")
                }
                if (versionDiffers || sizeDiffers) {
                    _appUpdate.value = AppUpdate.Available(tag, notes, emptyList(), apkSize ?: 0L, apkUrl)
                } else {
                    _appUpdate.value = AppUpdate.UpToDate(remoteVersion)
                }
            } catch (t: Throwable) {
                _appUpdate.value = AppUpdate.Failed(t.message ?: "Update check failed")
            }
        }
    }
    fun downloadAppUpdate() {
        val cur = _appUpdate.value as? AppUpdate.Available ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = workFile("update.apk")
                if (dest.exists()) dest.delete()
                val t0 = SystemClock.elapsedRealtime()
                ReleaseRepository.downloadUrl(cur.url, dest, cur.size) { done, total ->
                    val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    _appUpdate.value = AppUpdate.Downloading(cur.tag, done, total, done * 1000L / dt)
                }
                                updatePrefs.edit().putString("known_tag", cur.tag).apply()
                _appUpdate.value = AppUpdate.Downloaded(cur.tag, dest)
            } catch (t: Throwable) {
                _appUpdate.value = AppUpdate.Failed(t.message ?: "Download failed")
            }
        }
    }
    fun dismissUpdate() {
        _appUpdate.value = AppUpdate.Idle
    }
    fun consumeDownloaded() {
        _appUpdate.value = AppUpdate.Idle
    }
    fun markBundleTagKnown(tag: String) {
        updatePrefs.edit().putString("known_bundle_tag", tag).apply()
    }
    private fun appliedBuilds(abi: String): Map<String, String> {
        val raw = updatePrefs.getString("applied_builds_$abi", null) ?: return emptyMap()
        return runCatching {
            raw.split('\n').mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }
    private fun recordAppliedBuilds(abi: String, assets: List<IncrementalUpdateManager.AssetInfo>) {
        val merged = appliedBuilds(abi).toMutableMap()
        for (a in assets) if (a.build.isNotEmpty()) merged[a.cleanName] = a.build
        updatePrefs.edit()
            .putString(
                "applied_builds_$abi",
                merged.entries.joinToString("\n") { "${it.key}=${it.value}" },
            )
            .apply()
    }
    private fun mb(bytes: Long): String =
        "${(bytes / 1048576.0).let { "%.1f".format(java.util.Locale.US, it) }} MB"
    private val pluginIniManager = PluginIniManager(
        app = app,
        scope = viewModelScope,
        installPathProvider = { _installPath.value },
    )
    val pluginInis: StateFlow<List<PluginIniFile>> get() = pluginIniManager.pluginInis
    val iniText: StateFlow<String> get() = pluginIniManager.iniText
    val iniDirty: StateFlow<Boolean> get() = pluginIniManager.iniDirty
    val iniSavedAt: StateFlow<Long> get() = pluginIniManager.iniSavedAt
    fun openPluginIni(file: File) = pluginIniManager.openPluginIni(file)
    fun editIniText(text: String) = pluginIniManager.editIniText(text)
    fun savePluginIni(file: File) = pluginIniManager.savePluginIni(file)
    fun revertPluginIni(file: File) = pluginIniManager.revertPluginIni(file)
    fun loadPluginInis() = pluginIniManager.loadPluginInis()
    fun togglePluginLine(iniName: String, index: Int) = pluginIniManager.togglePluginLine(iniName, index)
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
        sb.append("Nexora ").append(ver).append("\n")
        sb.append("Game dir: ").append(_installPath.value).append("\n\n")
        fun tail(f: File, max: Int = 60): List<String> = try {
            if (!f.exists()) return listOf("(missing: ${f.path})")
            val lines = f.readLines()
            lines.takeLast(max)
        } catch (t: Throwable) {
            listOf("(unreadable: ${t.message})")
        }
        val base = File(_installPath.value)
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
        const val CACHE_TAG = "v2"
        const val GAME_CSTRIKE = "cstrike"
        const val GAME_CZERO = "czero"
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")
        const val ASSET_DATE_SLOP = 5 * 60 * 1000L
        const val GAMEDATA_DISABLED_SUFFIX = ".disabled"
        val GAMEDATA_VARIANT = Regex("offsets-cstrike-replugged\\.(arm64|arm32)\\.txt")
        const val LEGACY_GAMEDATA_NAME = "offsets-cstrike-replugged.txt"
        const val APP_RELEASE_REPO = "berkchy/nexora"
        const val CLIENT_APK_REPO = APP_RELEASE_REPO
        const val CLIENT_APK_ASSET = "CS16Client-Android.apk"
        const val CONTINUOUS_TAG = "continuous"
        const val CONTINUOUS_APK = "nexora_continuous.apk"
        const val RELEASE_APK = "nexora.apk"
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
    fun autoInstallAddons() {
        viewModelScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return@launch
            }
            val gameDir = File(_installPath.value)
            if (!gameDir.exists()) return@launch
            val bundle = bundleManager.loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value) ?: return@launch
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
            applyGamedataAbiPolicy(gameDir, _abi.value)
            scanAddonsStatus()
        }
    }
    data class AddonFileStatus(
        val path: String,
        val expected: Boolean,
        val installed: Boolean,
        val outdated: Boolean = false,
    )
    private val _addonFiles = MutableStateFlow<List<AddonFileStatus>>(emptyList())
    val addonFiles: StateFlow<List<AddonFileStatus>> = _addonFiles.asStateFlow()
    private val gamePrefs by lazy {
        getApplication<Application>().getSharedPreferences("game_prefs", Context.MODE_PRIVATE)
    }
    private fun savedGameId(): String =
        gamePrefs.getString("game_id", GAME_CSTRIKE)?.takeIf {
            it == GAME_CSTRIKE || it == GAME_CZERO
        } ?: GAME_CSTRIKE
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
    private fun resolveGameRoot(): String {
        val preferred = savedGameId()
        for (root in gameRootCandidates) {
            if (isGameDirInstalled(File(root, preferred))) return root
        }
        for (root in gameRootCandidates) {
            for (game in listOf(GAME_CSTRIKE, GAME_CZERO)) {
                if (isGameDirInstalled(File(root, game))) return root
            }
        }
        return "/storage/emulated/0/.xash"
    }
    private fun gameDirFor(gameId: String): String = "${resolveGameRoot()}/$gameId"
    private val _installPath = MutableStateFlow(gameDirFor(savedGameId()))
    val installPath: StateFlow<String> = _installPath.asStateFlow()
    val gameId: StateFlow<String> = _installPath
        .map { path -> if (path.endsWith("/czero")) GAME_CZERO else GAME_CSTRIKE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GAME_CSTRIKE)
    fun setGame(gameId: String) {
        if (gameId != GAME_CSTRIKE && gameId != GAME_CZERO) return
        gamePrefs.edit().putString("game_id", gameId).apply()
        _installPath.value = gameDirFor(gameId)
        runAutoDetectAbi()
        scanAddonsStatus()
    }
    fun scanAddonsStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val gameDir = File(_installPath.value)
            val addonsDir = File(gameDir, "addons")
            val bundle = bundleManager.loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value)
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