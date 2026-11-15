package com.yoriva.patcher

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.yoriva.data.IncrementalUpdateManager
import com.yoriva.data.ReleaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

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

class UpdateManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val workFile: (String) -> File,
) {
    private val updatePrefs by lazy {
        app.getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }

    private val _appUpdate = MutableStateFlow<AppUpdate>(AppUpdate.Idle)
    val appUpdate: StateFlow<AppUpdate> = _appUpdate.asStateFlow()

    fun checkAppUpdate() {
        val cur = _appUpdate.value
        if (cur is AppUpdate.Checking || cur is AppUpdate.Downloading || cur is AppUpdate.Downloaded) return
        scope.launch(Dispatchers.IO) {
            _appUpdate.value = AppUpdate.Checking
            try {
                val latest = ReleaseRepository.latestReleaseTag(PatcherViewModel.APP_RELEASE_REPO)
                val (tag, apkName) = if (latest != null) {
                    latest to PatcherViewModel.RELEASE_APK
                } else {
                    PatcherViewModel.CONTINUOUS_TAG to PatcherViewModel.CONTINUOUS_APK
                }
                val remoteVersion = ReleaseRepository.fetchText(
                    ReleaseRepository.assetUrl(PatcherViewModel.APP_RELEASE_REPO, tag, PatcherViewModel.VERSION_FILE)
                ) ?: throw IOException("Could not reach GitHub releases")
                val apkUrl = ReleaseRepository.assetUrl(PatcherViewModel.APP_RELEASE_REPO, tag, apkName)
                val apkSize = ReleaseRepository.probeSize(apkUrl)
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
        scope.launch(Dispatchers.IO) {
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

    private fun mb(bytes: Long): String =
        "${(bytes / 1048576.0).let { "%.1f".format(java.util.Locale.US, it) }} MB"
}
