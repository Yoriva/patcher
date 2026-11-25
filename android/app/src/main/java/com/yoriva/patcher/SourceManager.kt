package com.yoriva.patcher

import android.app.Application
import com.yoriva.data.ReleaseRepository
import com.yoriva.jobs.JobProgress
import com.yoriva.lib.ZipAnalyzer
import kotlinx.coroutines.CancellationException
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
        return candidates.firstOrNull { it.name == com.yoriva.patcher.PatcherViewModel.CLIENT_APK_ASSET }
            ?: candidates.maxByOrNull { it.lastModified() }
    }

    private fun apkProblem(file: File): String? = try {
        val info = ZipAnalyzer.analyze(file)
        if (info.abis.isNotEmpty() && info.abis.none { it in com.yoriva.patcher.PatcherViewModel.SUPPORTED_ABIS }) {
            "no supported native ABI (" + info.abis.joinToString(", ") + ")"
        } else {
            null
        }
    } catch (t: Throwable) {
        t.message?.takeIf { it.isNotBlank() } ?: "unreadable archive"
    }

    private fun useSourceFile(file: File, label: String) {
        val info = ZipAnalyzer.analyze(file)
        val supported = info.abis.filter { it in com.yoriva.patcher.PatcherViewModel.SUPPORTED_ABIS }
        if (info.abis.isNotEmpty() && supported.isEmpty()) {
            failPatch(
                "This APK has no supported native ABIs (found: " +
                    "${info.abis.joinToString(", ")}). " +
                    "Supported: ${com.yoriva.patcher.PatcherViewModel.SUPPORTED_ABIS.joinToString(", ")}."
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
            if (_source.value == null) {
                val info = runCatching { ZipAnalyzer.analyze(cached) }.getOrNull()
                if (info != null) {
                    val supported = info.abis.filter { it in com.yoriva.patcher.PatcherViewModel.SUPPORTED_ABIS }
                    _source.value = SourceInfo(
                        "${cached.name} (cached)",
                        cached.length(),
                        info.entryCount,
                        supported,
                    )
                }
            }
            val tag = PatcherViewModel.ASSETS_TAG
            val remote = ReleaseRepository.probe(
                ReleaseRepository.assetUrl(
                    PatcherViewModel.CLIENT_APK_REPO,
                    tag,
                    PatcherViewModel.CLIENT_APK_ASSET,
                ),
            )
            val problem = apkProblem(cached)
            val outdatedReason = if (problem == null) {
                ReleaseRepository.remoteMismatchReason(cached, remote, PatcherViewModel.ASSET_DATE_SLOP)
            } else {
                null
            }
            _sourceDownload.value = when {
                problem != null -> SourceDownloadState.Corrupt(PatcherViewModel.CLIENT_APK_ASSET, problem)
                outdatedReason != null -> SourceDownloadState.Outdated(
                    PatcherViewModel.CLIENT_APK_ASSET,
                    cached.length(),
                    remote?.size ?: 0L,
                    outdatedReason,
                )
                else -> SourceDownloadState.Done(PatcherViewModel.CLIENT_APK_ASSET)
            }
            if (_sourceDownload.value is SourceDownloadState.Done &&
                _receivedSource.value?.absolutePath != cached.absolutePath &&
                tag.isNotEmpty()
            ) {
                useSourceFile(cached, "${cached.name} ($tag)")
            }
        }
    }

    /**
     * Returns true if a valid, up-to-date client APK is already cached (loading it without
     * re-downloading), or downloads a fresh copy only when missing, corrupt, or outdated on GitHub.
     */
    suspend fun ensureSourceApkNow(
        reportJob: Boolean = true,
        externalProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        if (_sourceDownload.value is SourceDownloadState.Fetching ||
            _sourceDownload.value is SourceDownloadState.Downloading
        ) {
            return false
        }
        val tag = PatcherViewModel.ASSETS_TAG
        val url = ReleaseRepository.assetUrl(
            PatcherViewModel.CLIENT_APK_REPO,
            tag,
            PatcherViewModel.CLIENT_APK_ASSET,
        )
        val cached = cachedClientApk()
        if (cached != null && apkProblem(cached) == null) {
            val remote = ReleaseRepository.probe(url)
            if (ReleaseRepository.isRemoteMatch(cached, remote, PatcherViewModel.ASSET_DATE_SLOP)) {
                useSourceFile(cached, "${cached.name} ($tag)")
                _sourceDownload.value = SourceDownloadState.Done(cached.name)
                return true
            }
        }
        return downloadSourceApkNow(reportJob = reportJob, externalProgress = externalProgress)
    }

    suspend fun downloadSourceApkNow(
        reportJob: Boolean = true,
        externalProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        if (_sourceDownload.value is SourceDownloadState.Fetching ||
            _sourceDownload.value is SourceDownloadState.Downloading
        ) {
            return false
        }
        _sourceDownload.value = SourceDownloadState.Fetching
        if (reportJob) {
            JobProgress.begin(app, PatcherViewModel.JOB_CLIENT_APK, "Client APK", "Finding the release…")
        }
        return try {
            val repo = PatcherViewModel.CLIENT_APK_REPO
            val tag = PatcherViewModel.ASSETS_TAG
            val name = PatcherViewModel.CLIENT_APK_ASSET
            val url = ReleaseRepository.assetUrl(repo, tag, name)
            val remote = ReleaseRepository.probe(url)
            val total = remote?.size ?: 0L
            val dir = sourceCacheDir.apply { mkdirs() }
            val dest = File(dir, name)
            ReleaseRepository.downloadFile(url, dest, expectedSize = total) { done, size ->
                _sourceDownload.value = SourceDownloadState.Downloading(done, size)
                externalProgress?.invoke(done, size)
                if (reportJob) {
                    JobProgress.progress(app, PatcherViewModel.JOB_CLIENT_APK, done, size, "Downloading…")
                }
            }
            apkProblem(dest)?.let {
                dest.delete()
                throw IOException("The downloaded APK is broken: $it")
            }
            ReleaseRepository.writeFileStamp(dest, remote)
            useSourceFile(dest, "$name ($tag)")
            _sourceDownload.value = SourceDownloadState.Done(name)
            if (reportJob) {
                JobProgress.finish(app, PatcherViewModel.JOB_CLIENT_APK, "Client APK ready", apkPath = dest.path)
            }
            true
        } catch (c: CancellationException) {
            _sourceDownload.value = null
            if (reportJob) JobProgress.clear(PatcherViewModel.JOB_CLIENT_APK)
            refreshSourceApkStatus()
            throw c
        } catch (t: Throwable) {
            _sourceDownload.value = SourceDownloadState.Failed(t.message ?: "Download failed")
            if (reportJob) {
                JobProgress.finish(
                    app,
                    PatcherViewModel.JOB_CLIENT_APK,
                    t.message ?: "Download failed",
                    ok = false,
                )
            }
            false
        }
    }

}
