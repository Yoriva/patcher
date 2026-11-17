package com.yoriva.data
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.io.DEFAULT_BUFFER_SIZE
object ReleaseRepository {
    class GitHubRateLimited(
        val retryAfterSeconds: Long?,
        message: String,
    ) : IOException(message)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val webClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    suspend fun latestReleaseTag(repo: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://github.com/$repo/releases.atom")
                .header("User-Agent", "cs16-amxx-patcher")
                .get()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                Regex("/releases/tag/([^<\"]+)")
                    .findAll(body)
                    .mapNotNull { it.groupValues.getOrNull(1)?.substringBefore('"')?.trim() }
                    .firstOrNull { it.startsWith("v") }
            }
        } catch (_: Throwable) {
            null
        }
    }
    data class ReleaseDetails(
        val body: String,
        val publishedAtMillis: Long,
        val authorLogin: String,
    )
    data class CommitDetails(
        val sha: String,
        val message: String,
        val authorName: String,
        val authorLogin: String,
        val authoredAtMillis: Long,
    )
    suspend fun fetchReleaseDetails(repo: String, tag: String): ReleaseDetails? {
        val encoded = URLEncoder.encode(tag, "UTF-8")
        val body = fetchText("https://api.github.com/repos/$repo/releases/tags/$encoded") ?: return null
        return runCatching {
            val json = JSONObject(body)
            ReleaseDetails(
                body = json.optString("body"),
                publishedAtMillis = parseIsoDate(json.optString("published_at")),
                authorLogin = json.optJSONObject("author")?.optString("login").orEmpty(),
            )
        }.getOrNull()
    }
    suspend fun fetchCommitDetails(repo: String, ref: String): CommitDetails? {
        val encoded = URLEncoder.encode(ref, "UTF-8")
        val body = fetchText("https://api.github.com/repos/$repo/commits/$encoded") ?: return null
        return runCatching {
            val json = JSONObject(body)
            val commit = json.optJSONObject("commit")
            val author = commit?.optJSONObject("author")
            val githubAuthor = json.optJSONObject("author")
            CommitDetails(
                sha = json.optString("sha"),
                message = commit?.optString("message").orEmpty(),
                authorName = author?.optString("name").orEmpty(),
                authorLogin = githubAuthor?.optString("login").orEmpty(),
                authoredAtMillis = parseIsoDate(author?.optString("date").orEmpty()),
            )
        }.getOrNull()
    }
    private fun parseIsoDate(raw: String): Long = try {
        val cleaned = raw.replace(Regex("\\.\\d+Z$"), "Z")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.parse(cleaned)?.time ?: 0L
    } catch (_: Throwable) {
        0L
    }
    fun assetUrl(repo: String, tag: String, name: String): String =
        "https://github.com/$repo/releases/download/$tag/$name"
    suspend fun probeSize(url: String): Long? = probe(url)?.size
    data class RemoteInfo(
        val size: Long,
        val lastModifiedMillis: Long,
        val etag: String,
    )
    suspend fun probe(url: String): RemoteInfo? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "cs16-amxx-patcher")
                .head()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    val stamp = resp.header("Last-Modified")
                        ?.let { parseHttpDate(it) }
                        ?: 0L
                    RemoteInfo(
                        size = resp.body?.contentLength()?.coerceAtLeast(0L) ?: 0L,
                        lastModifiedMillis = stamp,
                        etag = resp.header("ETag")?.removeSurrounding("\"")?.trim() ?: "",
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
    private fun parseHttpDate(value: String): Long = try {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        fmt.parse(value)?.time ?: 0L
    } catch (_: Throwable) {
        0L
    }
    suspend fun fetchText(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "cs16-amxx-patcher")
                .get()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.trim().orEmpty().ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }
    private fun retryAfterSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toLongOrNull()?.let { return it }
        return try {
            val fmt = java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
            )
            val whenTo = fmt.parse(raw.trim()) ?: return null
            ((whenTo.time - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        } catch (_: Throwable) {
            null
        }
    }
    suspend fun downloadUrl(
        url: String,
        dest: File,
        knownSize: Long = 0,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "cs16-amxx-patcher")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 403) {
                val wait = retryAfterSeconds(resp.header("Retry-After"))
                val hint = wait?.let {
                    " (>1 min)" + if (it >= 60) " — retry in ${it / 60} min" else ""
                } ?: ""
                throw GitHubRateLimited(
                    wait,
                    "GitHub is rate-limiting this network right now (HTTP 403$hint). " +
                        "Wait a bit and try again.",
                )
            }
            if (!resp.isSuccessful) throw IOException("Download ${resp.code}: ${resp.message}")
            dest.parentFile?.mkdirs()
            val body = resp.body
                ?: throw IOException("Empty response body")
            val total = knownSize.takeIf { it > 0 }
                ?: body.contentLength().takeIf { it > 0 }
                ?: 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        return dest
    }
}