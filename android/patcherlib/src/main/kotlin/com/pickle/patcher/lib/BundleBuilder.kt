package com.pickle.patcher.lib
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
object BundleBuilder {
    fun build(
        entries: List<Pair<String, File>>,
        manifest: BundleManifest,
    ): Bundle {
        val files = HashMap<String, ByteArray>()
        for ((target, file) in entries) {
            files[target] = file.readBytes()
        }
        return Bundle(manifest, files)
    }
    fun manifestJson(manifest: BundleManifest): String = BundleManifest.encode(manifest)
    fun writeBundleZip(bundle: Bundle, out: File) {
        val fos = FileOutputStream(out)
        try {
            ZipOutputStream(fos).use { zos ->
                for ((name, content) in bundle.files) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content)
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("bundle.json"))
                zos.write(BundleManifest.encode(bundle.manifest).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        } finally {
            fos.close()
        }
    }
}