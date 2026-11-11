package com.pickle.patcher.patcher

import android.app.Application
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.IncrementalUpdateManager
import com.pickle.patcher.data.ReleaseRepository
import com.pickle.patcher.jobs.JobProgress
import com.pickle.patcher.lib.ZipAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class SourceManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val externalRoot: File,
    private val deviceAbi: String,
    private val abiProvider: () -> String,
    private val setAbi: (String) -> Unit,
    private val runAutoDetectAbi: () -> Unit,
    private val refreshAbiStatus: () -> Unit,
    private val failPatch: (String) -> Unit,
) {

    private val sourceCacheDir = File(externalRoot, "apk-source")
    private val legacySourceDir = File(app.filesDir, "apk-source")

    private val _source = MutableStateFlow<SourceInfo?>(null)
    val source: StateFlow<SourceInfo?> = _source.asStateFlow()

    private val _receivedSource = MutableStateFlow<File?>(null)

    private val _sourceDownload = MutableStateFlow<SourceDownloadState?>(null)
    val sourceDownload: StateFlow<SourceDownloadState?> = _sourceDownload.asStateFlow()

    fun receivedFile(): File? = _receivedSource.value

    private fun resolveAbi(sourceAbis: List<String>): String? = when {
        sourceAbis.isEmpty() -> null
        deviceAbi in sourceAbis -> deviceAbi
        else -> sourceAbis.first()
    }

    internal fun cachedClientApk(): File? {
        val candidates = listOfNotNull(sourceCacheDir, legacySourceDir).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
                ?.toList()
                ?: emptyList()
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name == com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_ASSET }
            ?: candidates.maxByOrNull { it.lastModified() }
    }

    private fun apkProblem(file: File): String? = try {
        val info = ZipAnalyzer.analyze(file)
        if (info.abis.isNotEmpty() && info.abis.none { it in com.pickle.patcher.patcher.PatcherViewModel.SUPPORTED_ABIS }) {
            "no supported native ABI (" + info.abis.joinToString(", ") + ")"
        } else {
            null
        }
    } catch (t: Throwable) {
        t.message?.takeIf { it.isNotBlank() } ?: "unreadable archive"
    }

    private fun useSourceFile(file: File, label: String) {
        val info = ZipAnalyzer.analyze(file)
        val supported = info.abis.filter { it in com.pickle.patcher.patcher.PatcherViewModel.SUPPORTED_ABIS }
        if (info.abis.isNotEmpty() && supported.isEmpty()) {
            failPatch(
                "This APK has no supported native ABIs (found: " +
                    "${info.abis.joinToString(", ")}). " +
                    "Supported: ${com.pickle.patcher.patcher.PatcherViewModel.SUPPORTED_ABIS.joinToString(", ")}."
            )
            _receivedSource.value = null
            return
        }
        if (supported.isNotEmpty()) {
            if (supported.size == 1) {
                setAbi(supported.first())
            } else if (abiProvider() !in supported) {
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
        scope.launch(Dispatchers.IO) {
            val cached = cachedClientApk()
            if (cached == null) {
                _sourceDownload.value = null
                return@launch
            }
            val tag = "extras"
            val remote = tag.let {
                ReleaseRepository.probe(ReleaseRepository.assetUrl(com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_REPO, it, com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_ASSET))
            }
            val recorded = cached.let { readSourceStamp(it) }
            val problem = cached.let { apkProblem(it) }
            val outdated = if (cached != null && remote != null) {
                outdatedReasons(cached, remote, recorded)
            } else {
                emptyList()
            }
            _sourceDownload.value = when {
                problem != null -> SourceDownloadState.Corrupt(com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_ASSET, problem)
                outdated.isNotEmpty() && cached != null -> SourceDownloadState.Outdated(
                    com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_ASSET,
                    cached.length(),
                    remote?.size ?: 0L,
                    outdated.joinToString(", "),
                )
                else -> SourceDownloadState.Done(com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_ASSET)
            }
            if (_sourceDownload.value is SourceDownloadState.Done &&
                cached != null &&
                _receivedSource.value?.absolutePath != cached.absolutePath &&
                tag.isNotEmpty()
            ) {
                useSourceFile(cached, "${cached.name} ($tag)")
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
                cached.lastModified() + com.pickle.patcher.patcher.PatcherViewModel.ASSET_DATE_SLOP < remote.lastModifiedMillis
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
        scope.launch(Dispatchers.IO) {
            _sourceDownload.value = SourceDownloadState.Fetching
            com.pickle.patcher.jobs.JobProgress.begin(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_CLIENT_APK, "Client APK", "Finding the release…")
            var partial: File? = null
            try {
                val repo = com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_REPO
                val tag = "extras"
                val name = com.pickle.patcher.patcher.PatcherViewModel.CLIENT_APK_ASSET
                val url = ReleaseRepository.assetUrl(repo, tag, name)
                val remote = ReleaseRepository.probe(url)
                val total = remote?.size ?: 0L
                val dir = sourceCacheDir.apply { mkdirs() }
                val dest = File(dir, name)
                val part = File(dir, "$name.part").also { it.delete() }
                partial = part
                ReleaseRepository.downloadUrl(url, part, total) { done, size ->
                    _sourceDownload.value = SourceDownloadState.Downloading(done, size)
                    com.pickle.patcher.jobs.JobProgress.progress(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_CLIENT_APK, done, size, "Downloading…")
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
                com.pickle.patcher.jobs.JobProgress.finish(app, com.pickle.patcher.patcher.PatcherViewModel.JOB_CLIENT_APK, "Client APK ready", apkPath = dest.path)
            } catch (t: Throwable) {
                partial?.delete()
                _sourceDownload.value = SourceDownloadState.Failed(t.message ?: "Download failed")
                com.pickle.patcher.jobs.JobProgress.finish(
                    app,
                    com.pickle.patcher.patcher.PatcherViewModel.JOB_CLIENT_APK,
                    t.message ?: "Download failed",
                    ok = false,
                )
            }
        }
    }

    fun clearSourceDownloadState() {
        _sourceDownload.value = null
    }
}
