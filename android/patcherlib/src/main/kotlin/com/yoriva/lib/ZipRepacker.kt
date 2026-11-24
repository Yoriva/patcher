package com.yoriva.lib
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.Deflater
object ZipRepacker {
    val LIBCS_ENTRY = "lib/arm64-v8a/libcs_android_arm64.so"
    private val LIBCS_PATCHES = listOf(
        0x240be4 to 20,   
        0x240c74 to 20,   
        0x240d04 to 20,   
        0x240d94 to 20,   
        0x240e24 to 20,   
        0x240eb4 to 20,   
        0x240fc8 to 8,    
        0x247bf8 to 0,    
        0x247c10 to 0,    
        0x247c28 to 0,    
        0x247c40 to 0,    
        0x247c58 to 0,    
        0x247c70 to 0,    
        0x247d20 to 0,    
        0x236a84 to 8,    
        0x238500 to 8,    
        0x236eb0 to 8,    
        0x23c240 to 8,    
        0x23d2d0 to 8,    
        0x23d75c to 9,    
        0x241354 to 8,    
    )
    data class Result(
        val output: File,
        val kept: List<String>,
        val removed: List<String>,
        val added: List<String>,
        val addedAssets: List<String> = emptyList(),
        val entriesTotal: Int,
        val alignedStored: Int,
        val padBytes: Long,
        var bytesWritten: Long = 0,
        var patchedLibs: List<String> = emptyList(),
    )
    fun repack(
        source: File,
        output: File,
        bundle: Bundle,
        exclude: ExcludeRule = ExcludeRule.DEFAULT,
        pruneAbiExcept: String? = null,
        extraAssetsZip: File? = null,
        progress: ((Long, Long) -> Unit)? = null,
        onEntry: ((index: Int, total: Int, name: String, kind: String) -> Unit)? = null,
        workers: Int = 1,
    ): Result {
        val src = ZipRaw.open(source) ?: throw IOException("Source APK could not be parsed")
        val srcLen = source.length()
        try {
            val removed = ArrayList<String>()
            val keptEntries = ArrayList<ZipRaw.ZipEntryInfo>()
            for ((name, entry) in src.entries) {
                val excluded = excludable(name, exclude) || prunedByAbi(name, pruneAbiExcept)
                if (excluded) removed.add(name) else keptEntries.add(entry)
            }
            val bundleTargets = bundle.manifest.entries.map { it.target }.toHashSet()
            val iterator = keptEntries.iterator()
            while (iterator.hasNext()) {
                val e = iterator.next()
                if (e.name in bundleTargets) {
                    removed.add(e.name)
                    iterator.remove()
                }
            }
            output.parentFile?.mkdirs()
            val raf = RandomAccessFile(output, "rw")
            val cdEntries = ArrayList<CdRecord>()
            var alignPadBytes = 0L
            var alignedStored = 0
            var bytesWritten = 0L
            try {
                raf.setLength(0)
                fun writeLocalHeader(
                    name: String,
                    method: Int,
                    compressedSize: Long,
                    uncompressedSize: Long,
                    crc: Long,
                    data: ByteArray,
                ) {
                    var localOffset = raf.filePointer
                    var extraLen = 0
                    if (method == 0) {
                        val unit = if (name.startsWith("lib/") && name.endsWith(".so")) 16384 else 4
                        val base = localOffset + 30 + name.length
                        val rem = (base % unit).toInt()
                        extraLen = if (rem == 0) 0 else (unit - rem)
                        alignPadBytes += extraLen
                        alignedStored++
                    }
                    val hdr = ByteArray(30)
                    val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x04034b50)
                    b.putShort(20)                                   
                    b.putShort(0)                                    
                    b.putShort(method.toShort())
                    b.putShort(0)                                    
                    b.putShort(0x0821)                               
                    b.putInt(crc.toInt())
                    b.putInt(compressedSize.toInt())
                    b.putInt(uncompressedSize.toInt())
                    b.putShort(name.length.toShort())
                    b.putShort(extraLen.toShort())
                    raf.write(hdr)
                    raf.write(name.toByteArray(Charsets.UTF_8))
                    if (extraLen > 0) raf.write(ByteArray(extraLen))
                    val dataOffset = raf.filePointer
                    raf.write(data)
                    cdEntries.add(
                        CdRecord(
                            name = name,
                            method = method,
                            compressedSize = compressedSize,
                            uncompressedSize = uncompressedSize,
                            crc = crc,
                            localOffset = localOffset,
                            dataOffset = dataOffset,
                        )
                    )
                    bytesWritten += 30 + name.length + extraLen + data.size
                }

                fun writeLocalHeaderFromChannel(
                    name: String,
                    method: Int,
                    compressedSize: Long,
                    uncompressedSize: Long,
                    crc: Long,
                    srcChannel: java.nio.channels.FileChannel,
                    srcDataOffset: Long,
                ) {
                    val localOffset = raf.filePointer
                    var extraLen = 0
                    if (method == 0) {
                        val unit = if (name.startsWith("lib/") && name.endsWith(".so")) 16384 else 4
                        val base = localOffset + 30 + name.length
                        val rem = (base % unit).toInt()
                        extraLen = if (rem == 0) 0 else (unit - rem)
                        alignPadBytes += extraLen
                        alignedStored++
                    }
                    val hdr = ByteArray(30)
                    val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x04034b50)
                    b.putShort(20)
                    b.putShort(0)
                    b.putShort(method.toShort())
                    b.putShort(0)
                    b.putShort(0x0821)
                    b.putInt(crc.toInt())
                    b.putInt(compressedSize.toInt())
                    b.putInt(uncompressedSize.toInt())
                    b.putShort(name.length.toShort())
                    b.putShort(extraLen.toShort())
                    raf.write(hdr)
                    raf.write(name.toByteArray(Charsets.UTF_8))
                    if (extraLen > 0) raf.write(ByteArray(extraLen))
                    val dataOffset = raf.filePointer

                    // Kernel-level Zero-Copy transfer directly between file descriptors
                    val dstChannel = raf.channel
                    var transferred = 0L
                    while (transferred < compressedSize) {
                        val count = srcChannel.transferTo(srcDataOffset + transferred, compressedSize - transferred, dstChannel)
                        if (count <= 0) break
                        transferred += count
                    }
                    raf.seek(dataOffset + compressedSize)

                    cdEntries.add(
                        CdRecord(
                            name = name,
                            method = method,
                            compressedSize = compressedSize,
                            uncompressedSize = uncompressedSize,
                            crc = crc,
                            localOffset = localOffset,
                            dataOffset = dataOffset,
                        )
                    )
                    bytesWritten += 30 + name.length + extraLen + compressedSize
                }
                val existingDirs = HashSet<String>()
                for (name in src.entries.keys) {
                    if (name.endsWith("/")) existingDirs.add(name)
                }
                fun parentsOf(path: String): List<String> {
                    val out = ArrayList<String>()
                    var acc = ""
                    for (p in path.split('/').dropLast(1)) {
                        acc += "$p/"
                        out.add(acc)
                    }
                    return out
                }
                val wantedDirs = LinkedHashSet<String>()
                for (be in bundle.manifest.entries) {
                    wantedDirs.addAll(parentsOf(be.target))
                }
                if (extraAssetsZip != null && extraAssetsZip.isFile) wantedDirs.add("assets/")
                for (dir in wantedDirs) {
                    if (dir in existingDirs || dir in bundleTargets) continue
                    existingDirs.add(dir)
                    writeLocalHeader(
                        name = dir,
                        method = 0,
                        compressedSize = 0L,
                        uncompressedSize = 0L,
                        crc = 0L,
                        data = ByteArray(0),
                    )
                }
                val total = keptEntries.size + bundle.manifest.entries.size
                var done = 0
                val patchedLibs = ArrayList<String>()
                val srcChannel = src.file.channel
                for (entry in keptEntries) {
                    onEntry?.invoke(done, total, entry.name, "keep")
                    if (entry.name == LIBCS_ENTRY) {
                        val content = src.readContent(entry)
                        val patched = patchLibCs(content)
                        if (patched != null) {
                            val compressed = deflate(patched)
                            writeLocalHeader(
                                name = entry.name,
                                method = 8,
                                compressedSize = compressed.size.toLong(),
                                uncompressedSize = patched.size.toLong(),
                                crc = crc32(patched),
                                data = compressed,
                            )
                            patchedLibs.add(entry.name)
                            done++
                            progress?.invoke(bytesWritten, srcLen)
                            continue
                        }
                    }
                    writeLocalHeaderFromChannel(
                        name = entry.name,
                        method = entry.method,
                        compressedSize = entry.compressedSize,
                        uncompressedSize = entry.uncompressedSize,
                        crc = entry.crc32,
                        srcChannel = srcChannel,
                        srcDataOffset = entry.dataOffset,
                    )
                    done++
                    progress?.invoke(bytesWritten, srcLen)
                }
                val added = ArrayList<String>()
                val bundleItems = bundle.manifest.entries.mapNotNull { be ->
                    bundle.resolveEntry(be)?.let { be to it }
                }
                val preparedBundle = parallelMap(bundleItems, workers) { (be, raw) ->
                    var content = raw
                    var patched = false
                    if (be.target == LIBCS_ENTRY) {
                        val guarded = patchLibCs(content)
                        if (guarded != null) {
                            content = guarded
                            patched = true
                        }
                    }
                    val stored = be.method == BundleManifest.Compression.STORED
                    PreparedBundleEntry(
                        target = be.target,
                        content = content,
                        compressed = if (stored) null else deflate(content),
                        stored = stored,
                        patched = patched,
                    )
                }
                for (entry in preparedBundle) {
                    onEntry?.invoke(done, total, entry.target, "add")
                    added.add(entry.target)
                    if (entry.patched)
                        patchedLibs.add(entry.target)
                    if (entry.stored) {
                        val crc = crc32(entry.content)
                        writeLocalHeader(entry.target, 0, entry.content.size.toLong(), entry.content.size.toLong(), crc, entry.content)
                    } else {
                        val compressed = entry.compressed ?: deflate(entry.content)
                        writeLocalHeader(entry.target, 8, compressed.size.toLong(), entry.content.size.toLong(), crc32(entry.content), compressed)
                    }
                    done++
                    progress?.invoke(bytesWritten, srcLen)
                }
                val addedAssets = ArrayList<String>()
                if (extraAssetsZip != null && extraAssetsZip.isFile) {
                    val assetNames = java.util.zip.ZipFile(extraAssetsZip).use { zf ->
                        zf.entries().asSequence()
                            .filter { !it.isDirectory && it.name.isNotBlank() }
                            .filter { !it.name.contains("..") && !it.name.startsWith("/") }
                            .map { it.name }
                            .filter { "assets/${it}" !in bundleTargets }
                            .toList()
                    }
                    val chunkSize = workers.coerceAtLeast(1)
                    var assetIndex = 0
                    while (assetIndex < assetNames.size) {
                        val chunkEnd = minOf(assetIndex + chunkSize, assetNames.size)
                        val names = assetNames.subList(assetIndex, chunkEnd)
                        val contents = java.util.zip.ZipFile(extraAssetsZip).use { zf ->
                            names.mapNotNull { name ->
                                val ze = zf.getEntry(name) ?: return@mapNotNull null
                                if (ze.isDirectory) return@mapNotNull null
                                name to zf.getInputStream(ze).use { it.readBytes() }
                            }
                        }
                        val preparedAssets = parallelMap(contents, workers) { (name, content) ->
                            PreparedAssetEntry(
                                target = "assets/$name",
                                content = content,
                                compressed = deflate(content),
                            )
                        }
                        for (asset in preparedAssets) {
                            onEntry?.invoke(done, done + assetNames.size, asset.target, "asset")
                            writeLocalHeader(
                                name = asset.target,
                                method = 8,
                                compressedSize = asset.compressed.size.toLong(),
                                uncompressedSize = asset.content.size.toLong(),
                                crc = crc32(asset.content),
                                data = asset.compressed,
                            )
                            addedAssets.add(asset.target)
                            done++
                            progress?.invoke(bytesWritten, srcLen)
                        }
                        assetIndex = chunkEnd
                    }
                }
                val cdOffset = raf.filePointer
                run {
                    val cb = java.io.ByteArrayOutputStream(cdEntries.size * 64)
                    val headerBuf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                    val headerArr = headerBuf.array()
                    for (rec in cdEntries) {
                        headerBuf.clear()
                        headerBuf.putInt(0x02014b50)
                        headerBuf.putShort(20)
                        headerBuf.putShort(20)
                        headerBuf.putShort(0)
                        headerBuf.putShort(rec.method.toShort())
                        headerBuf.putShort(0)
                        headerBuf.putShort(0x0821)
                        headerBuf.putInt(rec.crc.toInt())
                        headerBuf.putInt(rec.compressedSize.toInt())
                        headerBuf.putInt(rec.uncompressedSize.toInt())
                        headerBuf.putShort(rec.name.length.toShort())
                        headerBuf.putShort(0)
                        headerBuf.putShort(0)
                        headerBuf.putShort(0)
                        headerBuf.putShort(0)
                        headerBuf.putInt(0)
                        headerBuf.putInt(rec.localOffset.toInt())
                        cb.write(headerArr, 0, 46)
                        cb.write(rec.name.toByteArray(Charsets.UTF_8))
                    }
                    raf.write(cb.toByteArray())
                }
                val cdSize = raf.filePointer - cdOffset
                run {
                    val b = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x06054b50)
                    b.putShort(0)
                    b.putShort(0)
                    b.putShort(cdEntries.size.toShort())
                    b.putShort(cdEntries.size.toShort())
                    b.putInt(cdSize.toInt())
                    b.putInt(cdOffset.toInt())
                    b.putShort(0)
                    raf.write(b.array())
                }
                raf.fd.sync()
                return Result(
                    output = output,
                    kept = keptEntries.map { it.name },
                    removed = removed,
                    added = added,
                    addedAssets = addedAssets,
                    entriesTotal = cdEntries.size,
                    alignedStored = alignedStored,
                    padBytes = alignPadBytes,
                    bytesWritten = bytesWritten,
                    patchedLibs = patchedLibs,
                )
            } finally {
                raf.close()
            }
        } finally {
            src.close()
        }
    }
    private fun patchLibCs(content: ByteArray): ByteArray? {
        val out = content.copyOf()
        // 1. Fast path: Check if all exact offsets match
        var directMatch = true
        for ((off, rt) in LIBCS_PATCHES) {
            if (out.size < off + 4) { directMatch = false; break }
            val high = out[off + 3].toInt() and 0xFF
            val reg = out[off].toInt() and 0x1F
            if (high != 0xB4 || reg != rt) {
                directMatch = false
                break
            }
        }
        if (directMatch) {
            for ((off, _) in LIBCS_PATCHES) {
                out[off + 3] = 0xB6.toByte()
            }
            return out
        }

        // 2. Resilient fallback: Global delta shift detection
        // Cluster 1 has 6 consecutive checks at rt=20 spaced exactly by 0x90 bytes.
        // If the binary shifted due to an update, find the shift delta.
        val anchorOff = LIBCS_PATCHES[0].first // 0x240be4
        val maxShift = 0x40000 // +/- 256KB search window
        val searchStart = maxOf(4, anchorOff - maxShift)
        val searchEnd = minOf(out.size - 0x600, anchorOff + maxShift)
        var detectedDelta: Int? = null

        var probe = searchStart
        while (probe < searchEnd) {
            val h0 = out[probe + 3].toInt() and 0xFF
            val r0 = out[probe].toInt() and 0x1F
            if (h0 == 0xB4 && r0 == 20) {
                // Verify 0x90 stride on next 2 elements to confirm anchor
                val p1 = probe + 0x90
                val p2 = probe + 0x120
                if (p2 + 4 < out.size) {
                    val h1 = out[p1 + 3].toInt() and 0xFF
                    val r1 = out[p1].toInt() and 0x1F
                    val h2 = out[p2 + 3].toInt() and 0xFF
                    val r2 = out[p2].toInt() and 0x1F
                    if (h1 == 0xB4 && r1 == 20 && h2 == 0xB4 && r2 == 20) {
                        detectedDelta = probe - anchorOff
                        break
                    }
                }
            }
            probe += 4 // ARM64 instructions are 4-byte aligned
        }

        if (detectedDelta != null) {
            val delta = detectedDelta
            var deltaMatches = true
            for ((off, rt) in LIBCS_PATCHES) {
                val targetOff = off + delta
                if (targetOff < 0 || targetOff + 4 > out.size) { deltaMatches = false; break }
                val high = out[targetOff + 3].toInt() and 0xFF
                val reg = out[targetOff].toInt() and 0x1F
                if (high != 0xB4 || reg != rt) {
                    deltaMatches = false
                    break
                }
            }
            if (deltaMatches) {
                for ((off, _) in LIBCS_PATCHES) {
                    out[off + delta + 3] = 0xB6.toByte()
                }
                return out
            }
        }

        // 3. Windowed per-patch scan (+/- 512 bytes local search)
        var patchedCount = 0
        for ((off, rt) in LIBCS_PATCHES) {
            val window = 512
            val wStart = maxOf(0, (off - window) and -4)
            val wEnd = minOf(out.size - 4, (off + window) and -4)
            var foundAt = -1
            var p = wStart
            while (p <= wEnd) {
                val high = out[p + 3].toInt() and 0xFF
                val reg = out[p].toInt() and 0x1F
                if (high == 0xB4 && reg == rt) {
                    foundAt = p
                    break
                }
                p += 4
            }
            if (foundAt != -1) {
                out[foundAt + 3] = 0xB6.toByte()
                patchedCount++
            }
        }

        return if (patchedCount >= LIBCS_PATCHES.size / 2) out else null
    }
    private fun excludable(name: String, exclude: ExcludeRule): Boolean {
        for (p in exclude.exact) if (name == p) return true
        for (p in exclude.prefixes) {
            if (name.startsWith(p)) return true
        }
        return false
    }
    private fun prunedByAbi(name: String, keepAbi: String?): Boolean {
        if (keepAbi == null) return false
        if (!name.startsWith("lib/")) return false
        if (name.startsWith("lib/$keepAbi/")) return false
        return true
    }
    private fun deflate(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(64, data.size / 2))
        val def = Deflater(level, true)
        try {
            def.setInput(data)
            def.finish()
            val chunk = ByteArray(32768)
            while (!def.finished()) {
                val n = def.deflate(chunk)
                out.write(chunk, 0, n)
            }
        } finally {
            def.end()
        }
        return out.toByteArray()
    }
    private fun <T, R> parallelMap(
        items: List<T>,
        workers: Int,
        transform: (T) -> R,
    ): List<R> {
        if (workers <= 1 || items.size <= 1) return items.map(transform)
        val pool = Executors.newFixedThreadPool(minOf(workers, items.size))
        try {
            val futures = items.map { item -> pool.submit(Callable { transform(item) }) }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
    private fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }
    private data class PreparedBundleEntry(
        val target: String,
        val content: ByteArray,
        val compressed: ByteArray?,
        val stored: Boolean,
        val patched: Boolean,
    )
    private data class PreparedAssetEntry(
        val target: String,
        val content: ByteArray,
        val compressed: ByteArray,
    )
    private data class CdRecord(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val localOffset: Long,
        val dataOffset: Long,
    )
}