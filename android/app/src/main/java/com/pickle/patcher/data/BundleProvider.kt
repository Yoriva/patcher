package com.pickle.patcher.data
import android.content.Context
import android.os.Build
import android.os.Environment
import com.pickle.patcher.lib.Bundle
import java.io.File
const val NEXORA_ROOT = "nexora"
class BundleProvider(private val context: Context) {
    fun cacheDir(): File {
        val sd = File(Environment.getExternalStorageDirectory(), NEXORA_ROOT)
        val root = if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        ) {
            sd
        } else {
            File(context.getExternalFilesDir(null) ?: context.cacheDir, NEXORA_ROOT)
        }
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