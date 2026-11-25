package com.yoriva.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object IncrementalUpdateManager {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ReleaseAsset(
        val name: String = "",
        val size: Long = 0,
        val browser_download_url: String = "",
        val digest: String = "",
    )

    @Serializable
    data class ReleaseInfo(
        val tag_name: String = "",
        val assets: List<ReleaseAsset> = emptyList(),
    )

    @Serializable
    data class ManifestInfo(
        val version: String = "",
        val abi: String = "",
        val files: List<ManifestFile> = emptyList(),
    ) {
        @Serializable
        data class ManifestFile(
            val name: String = "",
            val asset: String = "",
            val sha256: String = "",
            val build: String = "",
            val size: Long = 0,
        )
    }

    data class AssetInfo(
        val assetName: String,
        val cleanName: String,
        val size: Long,
        val downloadUrl: String,
        val sha256: String = "",
        val build: String = "",
        val etag: String = "",
    )

    data class UpdateResult(
        val toDownload: List<AssetInfo>,
        val upToDate: List<AssetInfo>,
        val totalBytes: Long,
    )

    private const val REPO = "Yoriva/patcher"
    private const val PLUGIN_REPO = "Yoriva/yoriva-plugins"
    private const val PLUGIN_TAG = "continuous"

    suspend fun fetchReleaseAssets(tag: String, abi: String): List<AssetInfo> {
        val manifestName = if (abi == "arm64-v8a") "manifest.json" else "manifest-v7a.json"
        val manifest = runCatching { fetchManifestAssets(tag, manifestName) }.getOrNull()
        if (manifest != null) return manifest
        val api = fetchReleaseAssetsApi(tag, abi)
        if (api.isNotEmpty()) return api
        val expanded = fetchExpandedAssets(
            repo = REPO,
            tag = tag,
            filter = { name -> name.startsWith("${abi}_") && name.endsWith(".so") },
            cleanName = { name -> name.removePrefix("${abi}_") },
        )
        if (expanded != null) return expanded
        throw IOException("Could not load core library list for $abi")
    }

    suspend fun fetchPluginAssets(abi: String, tag: String = PLUGIN_TAG): List<AssetInfo> {
        val suffix = if (abi == "arm64-v8a") "arm64" else "arm"
        val release = fetchRelease(PLUGIN_REPO, tag)
        if (release != null) {
            return release.assets
                .filter { it.name.endsWith("_yoriva_${suffix}.so") }
                .map { asset ->
                    AssetInfo(
                        assetName = asset.name,
                        cleanName = asset.name,
                        size = asset.size,
                        downloadUrl = asset.browser_download_url,
                        sha256 = sha256Digest(asset.digest),
                    )
                }
        }
        val expanded = fetchExpandedAssets(
            repo = PLUGIN_REPO,
            tag = tag,
            filter = { name -> name.endsWith("_yoriva_${suffix}.so") },
            cleanName = { name -> name },
        )
        if (expanded != null) return expanded
        throw IOException("Could not load Yoriva Plugins from GitHub")
    }

    private suspend fun fetchManifestAssets(tag: String, manifestName: String): List<AssetInfo>? {
        val url = ReleaseRepository.assetUrl(REPO, tag, manifestName)
        val raw = ReleaseRepository.fetchJson(url, accept = "application/json") ?: return null
        val manifest = json.decodeFromString<ManifestInfo>(raw)
        return manifest.files.map { f ->
            AssetInfo(
                assetName = f.asset,
                cleanName = f.name,
                size = f.size,
                downloadUrl = ReleaseRepository.assetUrl(REPO, tag, f.asset),
                sha256 = f.sha256,
                build = f.build,
            )
        }
    }

    private suspend fun fetchReleaseAssetsApi(tag: String, abi: String): List<AssetInfo> {
        val release = fetchRelease(REPO, tag) ?: return emptyList()
        return release.assets
            .filter { it.name.startsWith("${abi}_") && it.name.endsWith(".so") }
            .map { asset ->
                AssetInfo(
                    assetName = asset.name,
                    cleanName = asset.name.removePrefix("${abi}_"),
                    size = asset.size,
                    downloadUrl = asset.browser_download_url,
                    sha256 = sha256Digest(asset.digest),
                )
            }
    }

    /**
     * Rate-limit-free fallback parsing GitHub's `releases/expanded_assets` HTML fragment and
     * probing each matching binary's remote metadata via HEAD.
     */
    private suspend fun fetchExpandedAssets(
        repo: String,
        tag: String,
        filter: (String) -> Boolean,
        cleanName: (String) -> String,
    ): List<AssetInfo>? {
        val expandedUrl = "https://github.com/$repo/releases/expanded_assets/$tag?t=${System.currentTimeMillis()}"
        val html = ReleaseRepository.fetchText(expandedUrl) ?: return null
        val hrefRegex = Regex("""/$repo/releases/download/$tag/([^"'\s<>]+)""")
        val names = hrefRegex.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .distinct()
            .filter(filter)
            .toList()
        return names.map { assetName ->
            val downloadUrl = ReleaseRepository.assetUrl(repo, tag, assetName)
            val probe = ReleaseRepository.probe(downloadUrl)
            AssetInfo(
                assetName = assetName,
                cleanName = cleanName(assetName),
                size = probe?.size ?: 0L,
                downloadUrl = downloadUrl,
                etag = probe?.etag.orEmpty(),
            )
        }
    }

    private suspend fun fetchRelease(repo: String, tag: String): ReleaseInfo? {
        val url = "https://api.github.com/repos/$repo/releases/tags/$tag"
        val body = ReleaseRepository.fetchJson(url, accept = "application/vnd.github+json") ?: return null
        return runCatching { json.decodeFromString<ReleaseInfo>(body) }.getOrNull()
    }

    private fun sha256Digest(digest: String): String =
        if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else ""

    fun diff(
        releaseAssets: List<AssetInfo>,
        libsDir: File,
        abi: String,
        appliedBuilds: Map<String, String> = emptyMap(),
    ): UpdateResult {
        val targetDir = File(libsDir, abi)
        val toDownload = mutableListOf<AssetInfo>()
        val upToDate = mutableListOf<AssetInfo>()
        for (asset in releaseAssets) {
            val fileOnDisk = File(targetDir, asset.cleanName)
            if (isUpToDate(fileOnDisk, asset, appliedBuilds[asset.cleanName])) {
                upToDate.add(asset)
            } else {
                toDownload.add(asset)
            }
        }
        return UpdateResult(
            toDownload = toDownload,
            upToDate = upToDate,
            totalBytes = toDownload.sumOf { it.size },
        )
    }

    fun isUpToDate(
        fileOnDisk: File,
        asset: AssetInfo,
        appliedBuild: String? = null,
    ): Boolean {
        if (!fileOnDisk.isFile) return false
        if (asset.size > 0 && fileOnDisk.length() != asset.size) return false
        if (asset.sha256.isNotEmpty()) {
            return sha256Of(fileOnDisk) == asset.sha256
        }
        if (asset.build.isNotEmpty() && !appliedBuild.isNullOrEmpty()) {
            return asset.build == appliedBuild
        }
        if (asset.etag.isNotEmpty()) {
            return ReleaseRepository.isRemoteMatch(
                fileOnDisk,
                ReleaseRepository.RemoteInfo(size = asset.size, lastModifiedMillis = 0L, etag = asset.etag),
            )
        }
        return true
    }

    fun sha256Of(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }

    private suspend fun downloadAndVerifyAsset(
        asset: AssetInfo,
        targetDir: File,
        onBytes: (written: Long, total: Long) -> Unit,
    ): File {
        val destFile = File(targetDir, asset.cleanName)
        ReleaseRepository.downloadFile(
            url = asset.downloadUrl,
            dest = destFile,
            expectedSize = asset.size,
            executable = true,
            onProgress = onBytes,
        )
        if (asset.sha256.isNotEmpty() && sha256Of(destFile) != asset.sha256) {
            destFile.delete()
            throw IOException("Checksum verification failed for ${asset.cleanName}")
        }
        if (asset.etag.isNotEmpty()) {
            ReleaseRepository.writeFileStamp(
                destFile,
                ReleaseRepository.RemoteInfo(size = destFile.length(), lastModifiedMillis = 0L, etag = asset.etag),
            )
        }
        return destFile
    }

    suspend fun downloadChanged(
        toDownload: List<AssetInfo>,
        libsDir: File,
        abi: String,
        onFileStart: (index: Int, asset: AssetInfo) -> Unit = { _, _ -> },
        onFileProgress: (index: Int, asset: AssetInfo, progress: Float) -> Unit = { _, _, _ -> },
        onProgress: (downloaded: Int, total: Int, bytesWritten: Long) -> Unit = { _, _, _ -> },
    ) {
        val targetDir = File(libsDir, abi).apply { mkdirs() }
        var completedBytes = 0L
        for ((index, asset) in toDownload.withIndex()) {
            onFileStart(index, asset)
            val destFile = downloadAndVerifyAsset(asset, targetDir) { written, total ->
                val expected = asset.size.takeIf { it > 0 } ?: total
                if (expected > 0) {
                    onFileProgress(index, asset, (written.toFloat() / expected).coerceIn(0f, 1f))
                }
                onProgress(index, toDownload.size, completedBytes + written)
            }
            completedBytes += destFile.length()
            onProgress(index + 1, toDownload.size, completedBytes)
        }
    }

    suspend fun downloadSingle(
        asset: AssetInfo,
        libsDir: File,
        abi: String,
        onProgress: (Float) -> Unit = {},
    ) {
        val targetDir = File(libsDir, abi).apply { mkdirs() }
        downloadAndVerifyAsset(asset, targetDir) { written, total ->
            val expected = asset.size.takeIf { it > 0 } ?: total
            if (expected > 0) {
                onProgress((written.toFloat() / expected).coerceIn(0f, 1f))
            }
        }
    }

    fun loadBundleFileMap(libsDir: File, abi: String, exclude: Set<String> = emptySet()): Map<String, File> {
        val targetDir = File(libsDir, abi)
        if (!targetDir.exists()) return emptyMap()
        val suffix = if (abi == "arm64-v8a") "arm64" else "armv7l"
        val files = HashMap<String, File>()
        targetDir.listFiles()?.filter {
            it.isFile && it.extension == "so" && it.name !in exclude
        }?.forEach { file ->
            val targetPath = getTargetPath(file.name, abi, suffix)
            if (targetPath != null) {
                files[targetPath] = file
            }
        }
        return files
    }

    private fun getTargetPath(name: String, abi: String, suffix: String): String? = when {
        name == "libmetadroid.so" -> "lib/$abi/libmetadroid_android_$suffix.so"
        name.startsWith("lib") && name.endsWith(".so") -> "lib/$abi/$name"
        name.endsWith(".so") -> "lib/$abi/lib$name"
        else -> null
    }
}
