package com.yoriva.patcher

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.yoriva.data.IncrementalUpdateManager
import com.yoriva.data.ReleaseRepository
import com.yoriva.lib.ZipAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

sealed interface AppUpdate {
    data object Idle : AppUpdate
    data class Checking(
        val manual: Boolean = false,
        val mandatory: Boolean = false,
    ) : AppUpdate
    data class Available(
        val tag: String,
        val notes: String,
        val commits: List<String>,
        val size: Long,
        val url: String,
        val remoteVersion: String = tag,
        val releaseNotes: String = "",
        val commitMessage: String = "",
        val commitSha: String = "",
        val authorLogin: String = "",
        val authorName: String = "",
        val updatedAtMillis: Long = 0L,
    ) : AppUpdate
    data class Downloading(
        val tag: String,
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
    ) : AppUpdate
    data class Downloaded(val tag: String, val file: File) : AppUpdate
    data class Installing(
        val tag: String,
        val file: File,
        val needsInstallPermission: Boolean = false,
        val errorMessage: String? = null,
    ) : AppUpdate
    data class UpToDate(val tag: String) : AppUpdate
    data class Failed(
        val message: String,
        val mandatory: Boolean = false,
        val canRetryDownload: Boolean = false,
    ) : AppUpdate
}

class UpdateManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val workFile: (String) -> File,
) {
    private val updatePrefs by lazy {
        app.getSharedPreferences(UPDATER_PREFS, Context.MODE_PRIVATE)
    }

    private val initialMandatory = isMandatoryLockedForCurrentApk()
    private val _appUpdate = MutableStateFlow<AppUpdate>(
        if (initialMandatory) AppUpdate.Checking(manual = false, mandatory = true) else AppUpdate.Idle,
    )
    val appUpdate: StateFlow<AppUpdate> = _appUpdate.asStateFlow()

    private val _lastCheckedAtMillis = MutableStateFlow(
        updatePrefs.getLong(KEY_LAST_CHECK_AT, 0L),
    )
    val lastCheckedAtMillis: StateFlow<Long> = _lastCheckedAtMillis.asStateFlow()

    private val _recentCommits = MutableStateFlow<List<ReleaseRepository.CommitDetails>>(emptyList())
    val recentCommits: StateFlow<List<ReleaseRepository.CommitDetails>> = _recentCommits.asStateFlow()

    private val _availableRelease = MutableStateFlow<AppUpdate.Available?>(null)
    val availableRelease: StateFlow<AppUpdate.Available?> = _availableRelease.asStateFlow()

    private val _commitsLoading = MutableStateFlow(false)
    val commitsLoading: StateFlow<Boolean> = _commitsLoading.asStateFlow()

    @Volatile
    private var commitsJob: Job? = null

    val currentVersionName: String
        get() = installedVersion() ?: "unknown"

    val currentApkSizeBytes: Long
        get() = installedApkSize() ?: 0L

    @Volatile
    private var checkJob: Job? = null

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var lastCheckElapsedMs: Long = 0L

    @Volatile
    private var lastCheckSucceeded: Boolean = false

    @Volatile
    private var lastAvailable: AppUpdate.Available? = null

    @Volatile
    private var usedSystemInstallerFallback = false

    @Volatile
    private var fallbackTriedForAttempt = false

    private val _pendingInstallIntent = MutableStateFlow<Intent?>(null)
    val pendingInstallIntent: StateFlow<Intent?> = _pendingInstallIntent.asStateFlow()

    fun consumePendingInstallIntent() {
        _pendingInstallIntent.value = null
    }

    init {
        scope.launch {
            confirmPrompts.collect { prompt ->
                _pendingInstallIntent.value = prompt
            }
        }
        scope.launch {
            installEvents.collect { (status, message) ->
                val cur = _appUpdate.value
                if (cur is AppUpdate.Installing) {
                    if (status == PackageInstaller.STATUS_SUCCESS) return@collect
                    // If PackageInstaller session is aborted or denied (common under OEM/BAL restrictions),
                    // fall back automatically once per attempt to the standard foreground ACTION_VIEW installer.
                    if (!fallbackTriedForAttempt && cur.file.isFile && cur.file.length() > 0L) {
                        fallbackTriedForAttempt = true
                        usedSystemInstallerFallback = true
                        val fallback = viewInstallerIntentFor(cur.file)
                        if (fallback != null) {
                            _pendingInstallIntent.value = fallback
                            _appUpdate.value = cur.copy(
                                needsInstallPermission = !canRequestPackageInstalls(),
                                errorMessage = null,
                            )
                            return@collect
                        }
                    }
                    val detail = message?.takeIf { it.isNotBlank() }
                        ?: if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                            "Installation was cancelled. You must install the update to continue using Yoriva."
                        } else {
                            "Installation failed (code $status). Tap Install to try again."
                        }
                    _appUpdate.value = cur.copy(
                        needsInstallPermission = !canRequestPackageInstalls(),
                        errorMessage = detail,
                    )
                }
            }
        }
    }

    fun checkAppUpdate(manual: Boolean = true) {
        if (checkJob?.isActive == true) {
            if (manual) {
                val cur = _appUpdate.value
                if (cur is AppUpdate.Checking && !cur.manual) {
                    _appUpdate.value = cur.copy(manual = true)
                }
            }
            return
        }
        val cur = _appUpdate.value
        if (cur is AppUpdate.Downloading || cur is AppUpdate.Downloaded || cur is AppUpdate.Installing) {
            return
        }
        if (!manual && cur is AppUpdate.Available) return

        val now = SystemClock.elapsedRealtime()
        val locked = isMandatoryLockedForCurrentApk() || lastAvailable != null
        if (!manual && !locked && lastCheckSucceeded && lastCheckElapsedMs > 0L && (now - lastCheckElapsedMs) < AUTO_CHECK_COOLDOWN_MS) {
            return
        }

        val mandatoryNow = locked
        checkJob = scope.launch(Dispatchers.IO) {
            _appUpdate.value = AppUpdate.Checking(manual = manual, mandatory = mandatoryNow)
            try {
                val latest = ReleaseRepository.latestReleaseTag(PatcherViewModel.APP_RELEASE_REPO)
                val (tag, apkName) = if (latest != null) {
                    latest to PatcherViewModel.RELEASE_APK
                } else {
                    PatcherViewModel.CONTINUOUS_TAG to PatcherViewModel.CONTINUOUS_APK
                }
                val versionUrl = "${ReleaseRepository.assetUrl(PatcherViewModel.APP_RELEASE_REPO, tag, PatcherViewModel.VERSION_FILE)}?t=${System.currentTimeMillis()}"
                val remoteVersion = ReleaseRepository.fetchText(versionUrl)
                    ?: throw IOException("Could not reach GitHub releases")
                val apkUrl = ReleaseRepository.assetUrl(PatcherViewModel.APP_RELEASE_REPO, tag, apkName)
                val apkSize = ReleaseRepository.probeSize(apkUrl)
                val ours = installedVersion()
                val localSize = installedApkSize()
                val needsUpdate = isRemoteNewer(
                    ours = ours,
                    remote = remoteVersion,
                    localSize = localSize,
                    remoteSize = apkSize,
                )
                val notes = buildString {
                    if (!ours.isNullOrBlank()) append("Installed: $ours")
                    if (localSize != null) append(" (${mb(localSize)})")
                    if (isNotEmpty()) append("  →  ")
                    append(remoteVersion)
                    if (apkSize != null && apkSize > 0) append(" (${mb(apkSize)})")
                }
                lastCheckElapsedMs = SystemClock.elapsedRealtime()
                lastCheckSucceeded = true
                val checkedWallMs = System.currentTimeMillis()
                _lastCheckedAtMillis.value = checkedWallMs
                updatePrefs.edit().putLong(KEY_LAST_CHECK_AT, checkedWallMs).apply()
                if (needsUpdate) {
                    persistMandatoryUpdate(remoteVersion)
                    val release = ReleaseRepository.fetchReleaseDetails(
                        PatcherViewModel.APP_RELEASE_REPO, tag,
                    )
                    val commit = ReleaseRepository.fetchCommitDetails(
                        PatcherViewModel.APP_RELEASE_REPO, tag,
                    )
                    val authorLogin = commit?.authorLogin?.takeIf { it.isNotBlank() }
                        ?: release?.authorLogin?.takeIf { it.isNotBlank() }
                        ?: ""
                    val authorName = commit?.authorName?.takeIf { it.isNotBlank() }
                        ?: authorLogin.ifBlank { "Yoriva" }
                    val available = AppUpdate.Available(
                        tag = tag,
                        notes = notes,
                        commits = emptyList(),
                        size = apkSize ?: 0L,
                        url = apkUrl,
                        remoteVersion = remoteVersion,
                        releaseNotes = release?.body.orEmpty(),
                        commitMessage = commit?.message.orEmpty(),
                        commitSha = commit?.sha.orEmpty(),
                        authorLogin = authorLogin,
                        authorName = authorName,
                        updatedAtMillis = commit?.authoredAtMillis?.takeIf { it > 0L }
                            ?: release?.publishedAtMillis
                            ?: 0L,
                    )
                    lastAvailable = available
                    _availableRelease.value = available
                    _appUpdate.value = available
                    loadRecentCommits(force = true)
                } else {
                    clearMandatoryUpdate()
                    lastAvailable = null
                    _availableRelease.value = null
                    _appUpdate.value = if (manual) AppUpdate.UpToDate(remoteVersion) else AppUpdate.Idle
                }
            } catch (t: Throwable) {
                lastCheckElapsedMs = SystemClock.elapsedRealtime()
                lastCheckSucceeded = false
                val stillMandatory = isMandatoryLockedForCurrentApk() || lastAvailable != null
                _appUpdate.value = if (manual || stillMandatory) {
                    AppUpdate.Failed(
                        message = t.message ?: "Update check failed",
                        mandatory = stillMandatory,
                        canRetryDownload = false,
                    )
                } else {
                    AppUpdate.Idle
                }
            }
        }
    }

    fun loadRecentCommits(force: Boolean = false) {
        if (commitsJob?.isActive == true) return
        if (!force && _recentCommits.value.isNotEmpty()) return
        commitsJob = scope.launch(Dispatchers.IO) {
            _commitsLoading.value = true
            try {
                val list = ReleaseRepository.fetchRecentCommits(PatcherViewModel.APP_RELEASE_REPO)
                if (list.isNotEmpty()) {
                    _recentCommits.value = list
                }
            } finally {
                _commitsLoading.value = false
            }
        }
    }

    fun onAppResumed() {
        val cur = _appUpdate.value
        if (cur is AppUpdate.Installing && cur.needsInstallPermission && canRequestPackageInstalls()) {
            installAppUpdate(cur.file)
            return
        }
        checkAppUpdate(manual = false)
    }

    fun downloadAppUpdate() {
        if (downloadJob?.isActive == true) return
        val available = (_appUpdate.value as? AppUpdate.Available)
            ?: lastAvailable
            ?: run {
                checkAppUpdate(manual = true)
                return
            }
        lastAvailable = available
        usedSystemInstallerFallback = false
        fallbackTriedForAttempt = false
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val dest = workFile("update.apk")
                if (dest.exists()) dest.delete()
                val t0 = SystemClock.elapsedRealtime()
                ReleaseRepository.downloadFile(available.url, dest, available.size) { done, total ->
                    val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    _appUpdate.value = AppUpdate.Downloading(available.tag, done, total, done * 1000L / dt)
                }
                val valid = runCatching { ZipAnalyzer.analyze(dest) }.isSuccess
                if (!valid) {
                    dest.delete()
                    throw IOException("Downloaded update APK is invalid or corrupted")
                }
                updatePrefs.edit().putString("known_tag", available.tag).apply()
                _appUpdate.value = AppUpdate.Downloaded(available.tag, dest)
            } catch (t: Throwable) {
                _appUpdate.value = AppUpdate.Failed(
                    message = t.message ?: "Download failed",
                    mandatory = true,
                    canRetryDownload = true,
                )
            }
        }
    }

    fun installAppUpdate(file: File) {
        val tag = when (val cur = _appUpdate.value) {
            is AppUpdate.Downloaded -> cur.tag
            is AppUpdate.Installing -> cur.tag
            is AppUpdate.Downloading -> cur.tag
            else -> lastAvailable?.tag ?: PatcherViewModel.CONTINUOUS_TAG
        }
        scope.launch(Dispatchers.IO) {
            if (!canRequestPackageInstalls()) {
                _appUpdate.value = AppUpdate.Installing(
                    tag = tag,
                    file = file,
                    needsInstallPermission = true,
                    errorMessage = null,
                )
                return@launch
            }
            _appUpdate.value = AppUpdate.Installing(
                tag = tag,
                file = file,
                needsInstallPermission = false,
                errorMessage = null,
            )
            fallbackTriedForAttempt = false
            updatePrefs.edit().putBoolean(RELAUNCH_AFTER_UPDATE, true).commit()
            if (usedSystemInstallerFallback) {
                val intent = viewInstallerIntentFor(file)
                if (intent != null) {
                    _pendingInstallIntent.value = intent
                    return@launch
                }
            }
            try {
                installPackage(file)
            } catch (t: Throwable) {
                usedSystemInstallerFallback = true
                val fallback = viewInstallerIntentFor(file)
                if (fallback != null && file.isFile && file.length() > 0L) {
                    fallbackTriedForAttempt = true
                    _pendingInstallIntent.value = fallback
                } else {
                    updatePrefs.edit().putBoolean(RELAUNCH_AFTER_UPDATE, false).apply()
                    _appUpdate.value = AppUpdate.Installing(
                        tag = tag,
                        file = file,
                        needsInstallPermission = !canRequestPackageInstalls(),
                        errorMessage = t.message ?: "Install failed",
                    )
                }
            }
        }
    }

    fun retryInstallUpdate() {
        val cur = _appUpdate.value as? AppUpdate.Installing ?: return
        if (cur.file.exists() && cur.file.length() > 0L) {
            if (cur.errorMessage != null) usedSystemInstallerFallback = true
            installAppUpdate(cur.file)
        } else {
            downloadAppUpdate()
        }
    }

    private fun viewInstallerIntentFor(apk: File): Intent? {
        if (!apk.isFile || apk.length() <= 0L) return null
        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun redownloadAppUpdate() {
        workFile("update.apk").delete()
        if (lastAvailable != null) {
            downloadAppUpdate()
        } else {
            checkAppUpdate(manual = true)
        }
    }

    private fun canRequestPackageInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        } else {
            true
        }

    private fun installedVersion(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName?.trim()
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).versionName?.trim()
        }
    }.getOrNull()

    private fun installedApkSize(): Long? = runCatching {
        File(app.applicationInfo.sourceDir).length().takeIf { it > 0L }
    }.getOrNull()

    private fun currentApkFingerprint(): String {
        val ver = installedVersion() ?: "unknown"
        val size = installedApkSize() ?: 0L
        return "$ver:$size"
    }

    private fun isMandatoryLockedForCurrentApk(): Boolean {
        val saved = updatePrefs.getString(KEY_REQUIRED_FINGERPRINT, null) ?: return false
        return saved == currentApkFingerprint()
    }

    private fun persistMandatoryUpdate(remoteVersion: String) {
        updatePrefs.edit()
            .putString(KEY_REQUIRED_FINGERPRINT, currentApkFingerprint())
            .putString(KEY_REQUIRED_TARGET, remoteVersion)
            .apply()
    }

    private fun clearMandatoryUpdate() {
        updatePrefs.edit()
            .remove(KEY_REQUIRED_FINGERPRINT)
            .remove(KEY_REQUIRED_TARGET)
            .apply()
    }

    private fun installPackage(file: File) {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("Update APK is missing")
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(app, AppInstallReceiver::class.java).apply {
                    action = AppInstallReceiver.ACTION_INSTALL_STATUS
                    setPackage(app.packageName)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pending = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    callback,
                    pendingFlags,
                )
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    fun dismissUpdate() {
        val cur = _appUpdate.value
        val locked = isMandatoryLockedForCurrentApk() ||
            lastAvailable != null ||
            cur is AppUpdate.Available ||
            cur is AppUpdate.Downloading ||
            cur is AppUpdate.Downloaded ||
            cur is AppUpdate.Installing ||
            (cur as? AppUpdate.Checking)?.mandatory == true ||
            (cur as? AppUpdate.Failed)?.mandatory == true
        if (!locked) {
            _appUpdate.value = AppUpdate.Idle
        }
    }

    fun markBundleTagKnown(tag: String) {
        updatePrefs.edit().putString("known_bundle_tag", tag).apply()
    }

    internal fun appliedBuilds(abi: String): Map<String, String> {
        val raw = updatePrefs.getString("applied_builds_$abi", null) ?: return emptyMap()
        return runCatching {
            raw.split('\n').mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    internal fun recordAppliedBuilds(abi: String, assets: List<IncrementalUpdateManager.AssetInfo>) {
        val merged = appliedBuilds(abi).toMutableMap()
        for (a in assets) if (a.build.isNotEmpty()) merged[a.cleanName] = a.build
        updatePrefs.edit()
            .putString(
                "applied_builds_$abi",
                merged.entries.joinToString("\n") { "${it.key}=${it.value}" },
            )
            .apply()
    }

    private fun isRemoteNewer(
        ours: String?,
        remote: String,
        localSize: Long?,
        remoteSize: Long?,
    ): Boolean {
        if (ours.isNullOrBlank()) return true
        if (ours == remote) {
            return remoteSize != null && remoteSize > 0L &&
                localSize != null && remoteSize != localSize
        }
        val localCont = Regex("^continuous-r(\\d+)$").find(ours)?.groupValues?.get(1)?.toLongOrNull()
        val remoteCont = Regex("^continuous-r(\\d+)$").find(remote)?.groupValues?.get(1)?.toLongOrNull()
        if (localCont != null && remoteCont != null) {
            return remoteCont > localCont
        }
        return true
    }

    private fun mb(bytes: Long): String =
        "${(bytes / 1048576.0).let { "%.1f".format(java.util.Locale.US, it) }} MB"

    companion object {
        private const val AUTO_CHECK_COOLDOWN_MS = 15_000L
        private const val KEY_REQUIRED_FINGERPRINT = "required_for_fingerprint"
        private const val KEY_REQUIRED_TARGET = "required_target_version"
        private const val KEY_LAST_CHECK_AT = "last_check_timestamp"
        private val installEvents = MutableSharedFlow<Pair<Int, String?>>(extraBufferCapacity = 8)
        private val confirmPrompts = MutableSharedFlow<Intent>(extraBufferCapacity = 4)

        internal fun notifyInstallStatus(status: Int, message: String?) {
            installEvents.tryEmit(status to message)
        }

        internal fun notifyConfirmPrompt(intent: Intent): Boolean =
            confirmPrompts.subscriptionCount.value > 0 && confirmPrompts.tryEmit(intent)
    }
}
