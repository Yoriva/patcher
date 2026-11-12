package com.pickle.patcher.data
import android.content.Context
import android.os.Environment
import com.pickle.patcher.lib.Bundle
import java.io.File
const val NEXORA_ROOT = "nexora"
internal fun migrateLegacyNexoraRoot(legacyRoot: File, newRoot: File) {
    if (!legacyRoot.isDirectory || legacyRoot.absolutePath == newRoot.absolutePath) return
    for (sub in listOf("apk-source", "downloads", "libs", "patcher")) {
        val legacyDir = File(legacyRoot, sub)
        if (!legacyDir.isDirectory) continue
        val targetDir = File(newRoot, sub)
        targetDir.mkdirs()
        val children = legacyDir.listFiles() ?: continue
        for (child in children) {
            val dest = File(targetDir, child.name)
            if (dest.exists()) continue
            child.renameTo(dest)
        }
        runCatching { legacyDir.delete() }
    }
    runCatching { legacyRoot.delete() }
}
class BundleProvider(private val context: Context) {
    fun cacheDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.cacheDir
        val root = File(base, NEXORA_ROOT)
        migrateLegacyNexoraRoot(File(Environment.getExternalStorageDirectory(), NEXORA_ROOT), root)
        val dir = File(root, "downloads")
        dir.mkdirs()
        return dir
    }
    fun cachedBundleFile(abi: String): File = File(cacheDir(), "amxx-bundle-$abi.zip")
    private fun legacyBundleFile(): File = File(cacheDir(), "amxx-bundle.zip")
    fun hasCachedBundle(abi: String): Boolean = cachedBundleFile(abi).exists()
    fun loadCachedBundle(abi: String): Bundle? {
        loadFrom(cachedBundleFile(abi))?.let { return it }
        val legacy = loadFrom(legacyBundleFile()) ?: return null
        return if (legacy.manifest.abi.isBlank() || legacy.manifest.abi == abi) legacy else null
    }
    fun loadFrom(file: File): Bundle? = try {
        Bundle.fromZip(file.readBytes())
    } catch (t: Throwable) {
        null
    }
}