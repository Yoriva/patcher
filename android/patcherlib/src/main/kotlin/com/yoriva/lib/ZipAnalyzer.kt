package com.yoriva.lib
import java.io.File
object ZipAnalyzer {
    data class ArchiveInfo(
        val name: String,
        val sizeBytes: Long,
        val entryCount: Int,
        val hasResourcesArsc: Boolean,
        val archAbi: String?,
        val abis: List<String>,
        val libCount: Int,
        val misalignedStored: List<String>,
    )
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a")
    fun analyze(file: File): ArchiveInfo {
        val zip = ZipRaw.open(file)
            ?: return ArchiveInfo(file.name, file.length(), 0, false, null, emptyList(), 0, emptyList())
        try {
            val arsc = zip.entries["resources.arsc"]
            val libs = zip.entries.keys.filter { it.startsWith("lib/") && it.endsWith(".so") }
            val detected = mutableSetOf<String>()
            for (lib in libs) {
                when {
                    lib.startsWith("lib/arm64-v8a/") || lib.startsWith("lib/arm64/") -> detected.add("arm64-v8a")
                    lib.startsWith("lib/armeabi-v7a/") || lib.startsWith("lib/armeabi/") -> detected.add("armeabi-v7a")
                }
            }
            if (detected.isEmpty() && libs.isNotEmpty()) {
                for (libPath in libs) {
                    val entry = zip.entries[libPath] ?: continue
                    try {
                        val header = zip.readContent(entry)
                        if (header.size >= 20 && header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                            val elfClass = header[4].toInt()
                            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                            if (elfClass == 2 || machine == 183) {
                                detected.add("arm64-v8a")
                            } else if (elfClass == 1 || machine == 40) {
                                detected.add("armeabi-v7a")
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            val abis = KNOWN_ABIS.filter { it in detected }
            val abi = abis.firstOrNull()
            val misaligned = zip.entries.values
                .filter { it.method == 0 && (it.dataOffset % 4) != 0L }
                .map { it.name }
            return ArchiveInfo(
                name = file.name,
                sizeBytes = file.length(),
                entryCount = zip.entries.size,
                hasResourcesArsc = arsc != null,
                archAbi = abi,
                abis = abis,
                libCount = libs.size,
                misalignedStored = misaligned,
            )
        } finally {
            zip.close()
        }
    }
    fun entryState(file: File, path: String): Triple<Boolean, Int, Long> {
        val zip = ZipRaw.open(file) ?: return Triple(false, -1, -1)
        try {
            val e = zip.entries[path] ?: return Triple(false, -1, -1)
            return Triple(true, e.method, e.dataOffset)
        } finally {
            zip.close()
        }
    }
}