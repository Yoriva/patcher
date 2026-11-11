package com.pickle.patcher.lib

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun buildZip(
    entries: List<Pair<String, ByteArray>>,
    storedEntries: Set<String> = emptySet(),
): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
        for ((name, data) in entries) {
            val e = ZipEntry(name)
            if (name in storedEntries) {
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                val crc = CRC32()
                crc.update(data)
                e.crc = crc.value
            }
            zos.putNextEntry(e)
            zos.write(data)
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

fun fakeApk(): ByteArray = buildZip(
    listOf(
        "AndroidManifest.xml" to buildMinimalAxml(),
        "classes.dex" to ByteArray(128) { 1 },
        "resources.arsc" to ByteArray(64) { 2 },
        "lib/arm64-v8a/libyapb.so" to ByteArray(256) { 3 },
        "lib/armeabi-v7a/libyapb.so" to ByteArray(256) { 4 },
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
    ),
    storedEntries = setOf("resources.arsc"),
)

fun repoFile(vararg parts: String): java.io.File {
    var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
    for (depth in 0..6) {
        val candidate = java.io.File(dir, parts.joinToString(java.io.File.separator))
        if (candidate.isFile) return candidate
        dir = dir.parentFile ?: break
    }
    throw java.io.FileNotFoundException("repository file not found: ${parts.joinToString("/")}")
}
