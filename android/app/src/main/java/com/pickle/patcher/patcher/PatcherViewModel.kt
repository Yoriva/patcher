package com.pickle.patcher.patcher
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.IncrementalUpdateManager
import com.pickle.patcher.data.NEXORA_ROOT
import com.pickle.patcher.data.ReleaseRepository
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.lib.ApkSignerTool
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.SigningKeystore
import com.pickle.patcher.lib.ZipAnalyzer
import com.pickle.patcher.lib.ZipRaw
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
    private val _bundle = MutableStateFlow<BundleState>(BundleState.None)
    val bundle: StateFlow<BundleState> = _bundle.asStateFlow()
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
    val loadedBundleAbi: String? get() = loadedBundle?.manifest?.abi?.ifBlank { null }
    private fun resolveAbi(sourceAbis: List<String>): String? = when {
        sourceAbis.isEmpty() -> null
        deviceAbi in sourceAbis -> deviceAbi
        else -> sourceAbis.first()
    }
    fun setAbi(abi: String) {
        if (abi !in SUPPORTED_ABIS || abi == _abi.value) return
        _abi.value = abi
        val b = loadedBundle
        if (b != null && b.manifest.abi.isNotBlank() && b.manifest.abi != abi) {
            loadedBundle = null
            _bundle.value = BundleState.None
        }
        viewModelScope.launch(Dispatchers.IO) {
            applyGamedataAbiPolicy(File(_installPath.value), abi)
            if (loadedBundle == null) useCachedBundle()
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
    private val _patchLog = MutableStateFlow<List<String>>(emptyList())
    val patchLog: StateFlow<List<String>> = _patchLog.asStateFlow()
    private val _patchHistory = MutableStateFlow<List<PatchHistoryEntry>>(emptyList())
    val patchHistory: StateFlow<List<PatchHistoryEntry>> = _patchHistory.asStateFlow()
    private val _patchCheck = MutableStateFlow<PatchCheck>(PatchCheck.Idle)
    private val _bundleVersion = MutableStateFlow("")
    val bundleVersion: StateFlow<String> = _bundleVersion.asStateFlow()
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()
    fun shouldReplayIntro(version: String): Boolean {
        if (version.isBlank()) return false
        if (_bundleVersion.value != version) {
            _bundleVersion.value = version
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
    val patchCheck: StateFlow<PatchCheck> = _patchCheck.asStateFlow()
    private val _abiStatus = MutableStateFlow<List<AbiStatus>>(emptyList())
    val abiStatus: StateFlow<List<AbiStatus>> = _abiStatus.asStateFlow()
    private var downloadingAbi: String? = null
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
                hasBundle = count > 0 || bundleProvider.hasCachedBundle(a) || loadedBundle?.manifest?.abi == a,
                selected = a == selected,
                downloading = downloadingAbi == a,
                downloadPercent = if (downloadingAbi == a) {
                    (_bundle.value as? BundleState.Downloading)?.percent ?: 0f
                } else 0f,
            )
        }
    }
    private val _libs = MutableStateFlow<List<LibInfo>>(emptyList())
    private val _customLibs = MutableStateFlow<List<CustomLib>>(emptyList())
    val customLibs: StateFlow<List<CustomLib>> = _customLibs.asStateFlow()
    val libs: StateFlow<List<LibInfo>> = _libs.asStateFlow()
    private val _libsChecking = MutableStateFlow(false)
    val libsChecking: StateFlow<Boolean> = _libsChecking.asStateFlow()
    fun refreshLibStatus() {
        scanLibs(checkRemote = true)
    }
    private val patcherPrefs by lazy {
        getApplication<Application>().getSharedPreferences("patcher_prefs", Context.MODE_PRIVATE)
    }
    private val _patchWorkers = MutableStateFlow(0)
    val patchWorkers: StateFlow<Int> = _patchWorkers.asStateFlow()
    private var loadedBundle: Bundle? = null
    private val compilerManager = CompilerManager(
        app = app,
        bundleProvider = bundleProvider,
        scope = viewModelScope,
        abiProvider = { _abi.value },
        loadedBundleProvider = { loadedBundle },
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
    init {
        val savedPatch = patcherPrefs.getInt("patch_workers", 0)
        _patchWorkers.value = if (savedPatch > 0) savedPatch.coerceIn(1, maxPatchWorkers()) else maxPatchWorkers()
    }
    fun maxPatchWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    fun effectivePatchWorkers(): Int =
        _patchWorkers.value.coerceIn(1, maxPatchWorkers())
    fun setPatchWorkers(count: Int) {
        val clamped = count.coerceIn(1, maxPatchWorkers())
        if (clamped == _patchWorkers.value) return
        _patchWorkers.value = clamped
        patcherPrefs.edit().putInt("patch_workers", clamped).apply()
    }
    private var lastReport: ApkPatcher.PatchReport? = null
    val hasCachedBundle: Boolean get() = bundleProvider.hasCachedBundle(_abi.value)
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
    private val libsDir = File(externalRoot, "libs")
    init {
        scanLibs()
        useCachedBundle()
        scanCustomLibs()
        runAutoDetectAbi()
    }
    private val _releaseNote = MutableStateFlow<String?>(null)
    val releaseNote: StateFlow<String?> = _releaseNote.asStateFlow()
    private val _patch = MutableStateFlow<PatchUiState>(PatchUiState.Idle)
    val patch: StateFlow<PatchUiState> = _patch.asStateFlow()
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
            _patch.value = PatchUiState.Failed(
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
                        "Download stopped at %.1f MB of %.1f MB".format(
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
    fun useCachedBundle() {
        val abi = _abi.value
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
        viewModelScope.launch(Dispatchers.IO) {
            val abi = _abi.value
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
                                File(targetDir, name), asset, appliedBuilds(_abi.value)[name]
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
    fun refreshSingleLib(libName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadLib(libName)
        }
    }
    private suspend fun downloadLib(libName: String) {
        try {
            val tagName = "libs"
            val assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, _abi.value)
            val asset = assets.find { it.cleanName == libName } ?: return
            _libs.value = _libs.value.map {
                if (it.name == libName) it.copy(downloading = true, downloadProgress = 0f) else it
            }
            IncrementalUpdateManager.downloadSingle(asset, libsDir, _abi.value) { p ->
                _libs.value = _libs.value.map {
                    if (it.name == libName) it.copy(downloading = true, downloadProgress = p) else it
                }
            }
            val fileOnDisk = File(File(libsDir, _abi.value), libName)
            val newSize = if (fileOnDisk.exists()) fileOnDisk.length() else 0L
            val upToDate = IncrementalUpdateManager.isUpToDate(
                fileOnDisk, asset, appliedBuilds(_abi.value)[libName]
            )
            recordAppliedBuilds(_abi.value, listOf(asset))
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
        downloadingAbi = _abi.value
        refreshAbiStatus()
        viewModelScope.launch(Dispatchers.IO) {
            _bundle.value = BundleState.Downloading(0.04f)
            var jobStarted = false
            try {
                val tagName = "libs"
                _releaseNote.value = tagName
                val assets = IncrementalUpdateManager.fetchReleaseAssets(tagName, _abi.value)
                if (assets.isEmpty()) throw IOException("No .so assets found for ABI ${_abi.value}")
                _bundle.update { BundleState.Downloading(0.1f, tagName) }
                val diff = IncrementalUpdateManager.diff(
                    assets, libsDir, _abi.value, appliedBuilds(_abi.value)
                )
                if (diff.toDownload.isEmpty()) {
                    val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                    if (files.isEmpty()) throw IOException("No .so files found in libs/")
                    val b = buildBundleFromFileMap(files, _abi.value)
                    markBundleTagKnown(tagName)
                    recordAppliedBuilds(_abi.value, assets)
                    _bundle.value = BundleState.Loaded
                    loadedBundle = b
                    scanLibs()
                    return@launch
                }
                JobProgress.begin(app, JOB_LIBS, "Libraries", "Downloading ${diff.toDownload.size} files…")
                jobStarted = true
                IncrementalUpdateManager.downloadChanged(
                    diff.toDownload, libsDir, _abi.value,
                    onFileStart = { index, asset ->
                        markLibDownloading(asset.cleanName, 0f)
                        JobProgress.detail(app, JOB_LIBS, asset.cleanName)
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
                        JobProgress.progress(
                            app,
                            JOB_LIBS,
                            (overall * 100).toLong().toInt().toLong().coerceIn(0, 99),
                            100L,
                            "${asset.cleanName} · ${
                                (fileProgress * 100).toInt().coerceIn(0, 100)
                            }%",
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
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                if (files.isEmpty()) throw IOException("No .so files found after download")
                val b = buildBundleFromFileMap(files, _abi.value)
                markBundleTagKnown(tagName)
                recordAppliedBuilds(_abi.value, assets)
                _bundle.value = BundleState.Ready(
                    "Updated ${diff.toDownload.size} files", b.manifest.entries.size, b.manifest.version
                )
                loadedBundle = b
                downloadingAbi = null
                _bundleVersion.value = b.manifest.version
                scanLibs()
                refreshAbiStatus()
                JobProgress.finish(app, JOB_LIBS, "Libraries ready")
            } catch (t: Throwable) {
                val files = IncrementalUpdateManager.loadBundleFileMap(libsDir, _abi.value)
                if (files.isNotEmpty()) {
                    val b = buildBundleFromFileMap(files, _abi.value)
                    loadedBundle = b
                    _bundle.value = BundleState.Loaded
                    scanLibs()
                    if (jobStarted) {
                        JobProgress.finish(app, JOB_LIBS, "Using the libraries already on disk")
                    }
                } else {
                    _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
                    if (jobStarted) {
                        JobProgress.finish(app, JOB_LIBS, t.message ?: "Download failed", ok = false)
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
        val modSuffix = if (abi == "arm64-v8a") "amd64" else "arm"
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
            com.pickle.patcher.lib.BundleManifest.BundleEntry(
                source = targetPath,
                target = targetPath,
                method = com.pickle.patcher.lib.BundleManifest.Compression.STORED,
                required = true,
                description = desc,
            )
        }
        val manifest = com.pickle.patcher.lib.BundleManifest(
            version = "live",
            game = "cs16client",
            abi = abi,
            entries = entries,
        )
        return Bundle(manifest, emptyMap(), files)
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
    private fun customLibDir(): File =
        File(getApplication<Application>().filesDir, "custom-libs").apply { mkdirs() }
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
        val abi = _abi.value
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
    private val _customLibProblem = MutableStateFlow<String?>(null)
    val customLibProblem: StateFlow<String?> = _customLibProblem.asStateFlow()
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
    private fun scanCustomLibs() {
        val abi = _abi.value
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
            .map { "lib/${_abi.value}/${it.name}" to it.file }
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
        val abi = _abi.value
        return _customLibs.value.filter {
            val verdict = com.pickle.patcher.lib.validateElf(it.file, abi)
            verdict !is com.pickle.patcher.lib.ElfVerdict.Ok
        }
    }
    private fun componentKeyFor(target: String): String {
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
        java.util.zip.ZipFile(apk).use { zf ->
            zf.getEntry("assets/touch.cfg") != null
        }
    }.getOrDefault(false)
    private fun zipIsComplete(zip: File): Boolean {
        if (!zip.isFile || zip.length() == 0L) return false
        return runCatching {
            java.util.zip.ZipFile(zip).use { zf ->
                requiredGameAssets.all { zf.getEntry(it) != null }
            }
        }.getOrDefault(false)
    }
    private suspend fun ensureGameAssetsZip(
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
    private fun unzipInto(zip: File, target: File): Int {
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
                if (PROTECTED_ADDON_CONFIGS.contains(name) && out.exists()) return@forEach
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }
    private fun patchMetamodConfig(gameDir: File, abi: String) {
        val suffix = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            else -> return
        }
        val isCounterStrike = gameDir.name == GAME_CSTRIKE
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
    private fun applyGamedataAbiPolicy(gameDir: File, abi: String): Boolean {
        val customDir = File(gameDir, "addons/amxmodx/data/gamedata/common.games/custom")
        val files = customDir.listFiles() ?: return false
        val wanted = if (abi == "arm64-v8a") "arm64" else "arm32"
        var changed = false
        for (file in files) {
            if (!file.isFile) continue
            if (file.name == LEGACY_GAMEDATA_NAME) {
                if (file.delete()) changed = true
                continue
            }
            val name = file.name.removeSuffix(GAMEDATA_DISABLED_SUFFIX)
            val match = GAMEDATA_VARIANT.matchEntire(name) ?: continue
            val variant = match.groupValues[1]
            val target = if (variant == wanted) name else name + GAMEDATA_DISABLED_SUFFIX
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
            tick.kind.isEmpty() && detail.isNotBlank() -> "\u2192 ${tick.step.name.lowercase()} \u00b7 $detail"
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
    private val _replacePrompt = MutableStateFlow<File?>(null)
    val replacePrompt: StateFlow<File?> = _replacePrompt.asStateFlow()
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
    fun startPatch(selectedComponentKeys: Set<String>? = null) {
        val src = _receivedSource.value ?: return
        val b = loadedBundle ?: return
        val selAbi = _abi.value
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
            val kept = b.manifest.entries.filter { componentKeyFor(it.target) in selectedComponentKeys }
            b.withEntries(kept)
        }
        val customs = customLibEntries(b, selectedComponentKeys ?: emptySet())
        val finalBundle = if (customs.isEmpty()) {
            effectiveBundle
        } else {
            val customTargets = customs.map { it.first }.toSet()
            val keptEntries = effectiveBundle.manifest.entries.filterNot { it.target in customTargets }
            val addedEntries = customs.map { (target, file) ->
                com.pickle.patcher.lib.BundleManifest.BundleEntry(
                    source = "custom/${file.name}",
                    target = target,
                    method = com.pickle.patcher.lib.BundleManifest.Compression.STORED,
                    required = true,
                    description = "User library",
                )
            }
            val extraDisk = customs.associate { (target, file) -> "custom/${file.name}" to file }
            com.pickle.patcher.lib.Bundle(
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
        viewModelScope.launch(Dispatchers.IO) {
            JobProgress.begin(app, JOB_PATCH, "Patching", "Analyzing the source APK…")
            _patch.value = PatchUiState.Running(ApkPatcher.Step.ANALYZE, 0f)
            try {
                val gameDir = File(_installPath.value)
                val assetsZip = if (sourceApkShipsAssets(src)) {
                    JobProgress.detail(app, JOB_PATCH, "Source APK ships the game content")
                    null
                } else {
                    ensureGameAssetsZip(
                        gameDir = gameDir,
                        onStatus = { msg ->
                            JobProgress.detail(app, JOB_PATCH, msg)
                        },
                        onProgress = { done, total ->
                            JobProgress.progress(app, JOB_PATCH, done, total, "Downloading game content…")
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
                        JobProgress.detail(app, JOB_PATCH, patchStepLabel(tick))
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
                        listOf("\u2192 inject \u00b7 ${report.addedAssetFiles} game content files written into assets/")
                    } else {
                        emptyList()
                    }
                _patch.value = PatchUiState.Done(report, finished)
                persistPatchRun(selAbi, report, finished)
                JobProgress.finish(
                    app,
                    JOB_PATCH,
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
                JobProgress.finish(app, JOB_PATCH, t.message ?: "Patch failed", ok = false)
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
    private var installAfterPatch = false
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
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
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
            val file = File(_installPath.value, "patch-history.json")
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
            val dir = File(_installPath.value)
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
        val b = loadedBundle ?: return null
        val src = _receivedSource.value ?: return null
        val abi = _abi.value
        val payload = b.files.entries
            .filter { it.key.contains(abi) || it.key.startsWith("addons/") }
            .sumOf { it.value.size.toLong() }
        return PatchSummary(
            abi = abi,
            source = src.name,
            sourceSize = src.length(),
            components = b.manifest.entries.groupBy { componentKeyFor(it.target) }.size,
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val abi = _abi.value
                val v = ApkSignerTool.verify(out)
                val zip = ZipRaw.open(out)
                val prefix = "lib/$abi/"
                val present = zip?.entries?.keys?.count { it.startsWith(prefix) && it.endsWith(".so") } ?: 0
                zip?.close()
                val expected = loadedBundle?.manifest?.entries?.count { it.target.startsWith(prefix) } ?: present
                val engine = File(_installPath.value, "engine.log")
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
        "${(bytes / 1048576.0).let { "%.1f".format(it) }} MB"
    data class PluginLine(val text: String, val enabled: Boolean, val editable: Boolean, val isPlugin: Boolean)
    data class PluginIniFile(val name: String, val file: File, val lines: List<PluginLine>)
    private val _pluginInis = MutableStateFlow<List<PluginIniFile>>(emptyList())
    val pluginInis: StateFlow<List<PluginIniFile>> = _pluginInis.asStateFlow()
    private val _iniText = MutableStateFlow("")
    val iniText: StateFlow<String> = _iniText.asStateFlow()
    private val _iniDirty = MutableStateFlow(false)
    val iniDirty: StateFlow<Boolean> = _iniDirty.asStateFlow()
    private val _iniSavedAt = MutableStateFlow(0L)
    val iniSavedAt: StateFlow<Long> = _iniSavedAt.asStateFlow()
    fun openPluginIni(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _iniText.value = try {
                if (file.exists()) file.readText() else ""
            } catch (t: Throwable) {
                "# could not read ${file.name}\n"
            }
            _iniDirty.value = false
        }
    }
    fun editIniText(text: String) {
        _iniText.value = text
        _iniDirty.value = true
    }
    fun savePluginIni(file: File) {
        val text = _iniText.value
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                file.parentFile?.mkdirs()
                file.writeText(text)
                true
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                _iniDirty.value = false
                _iniSavedAt.value = System.currentTimeMillis()
                loadPluginInis()
            }
        }
    }
    fun revertPluginIni(file: File) = openPluginIni(file)
    fun loadPluginInis() {
        viewModelScope.launch(Dispatchers.IO) {
            val configs = File(File(_installPath.value), "addons/amxmodx/configs")
            val files = configs.listFiles { f ->
                f.isFile && f.name.startsWith("plugins") && f.name.endsWith(".ini")
            }?.sortedBy { it.name } ?: emptyList()
            _pluginInis.value = files.map { f ->
                PluginIniFile(
                    name = f.name,
                    file = f,
                    lines = f.readLines().map { line ->
                        val t = line.trim()
                        val stripped = t.removePrefix(";").trim()
                        val first = stripped.split(Regex("\\s+")).firstOrNull().orEmpty()
                        if (first.endsWith(".amxx", ignoreCase = true)) {
                            PluginLine(line, enabled = !t.startsWith(";"), editable = true, isPlugin = true)
                        } else {
                            PluginLine(line, enabled = true, editable = false, isPlugin = false)
                        }
                    }
                )
            }
        }
    }
    fun togglePluginLine(iniName: String, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _pluginInis.value
            val ini = current.firstOrNull { it.name == iniName } ?: return@launch
            if (index !in ini.lines.indices) return@launch
            val line = ini.lines[index]
            if (!line.editable) return@launch
            val updated = ini.lines.toMutableList()
            updated[index] = if (line.enabled) {
                line.copy(text = ";" + line.text, enabled = false)
            } else {
                line.copy(text = line.text.replaceFirst(Regex("^\\s*;"), ""), enabled = true)
            }
            try {
                ini.file.writeText(updated.joinToString("\n") { it.text })
            } catch (_: Throwable) {
                return@launch
            }
            _pluginInis.value = current.map {
                if (it.name == iniName) it.copy(lines = updated) else it
            }
        }
    }
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
            val bundle = loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value) ?: return@launch
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
            val bundle = loadedBundle ?: bundleProvider.loadCachedBundle(_abi.value)
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