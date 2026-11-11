package com.pickle.patcher.lib

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun u16(v: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
private fun u32(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

private fun u16le(v: Int): ByteArray =
    byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

private fun u32le(v: Long): ByteArray =
    byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

private fun writeU16(out: ByteArrayOutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v ushr 8) and 0xFF)
}

private class StringPoolBuilder {
    private val strings = mutableListOf<String>()

    fun add(s: String): Int {
        val existing = strings.indexOf(s)
        if (existing >= 0) return existing
        strings.add(s)
        return strings.size - 1
    }

    fun build(): ByteArray {
        val headerSize = 28
        val offsetsSectionSize = strings.size * 4
        val stringsStart = headerSize + offsetsSectionSize

        val chunks = mutableListOf<ByteArray>()
        val offsets = mutableListOf<Int>()
        var offset = 0
        for (s in strings) {
            val utf16 = s.toByteArray(Charsets.UTF_16LE)
            val buf = ByteArrayOutputStream()
            val charCount = utf16.size / 2
            if (charCount <= 0x7FFF) {
                writeU16(buf, charCount)
            } else {
                writeU16(buf, 0x8000 or (charCount ushr 16))
                writeU16(buf, charCount and 0xFFFF)
            }
            buf.write(utf16)
            buf.write(0)
            buf.write(0)
            while (buf.size() % 4 != 0) buf.write(0)
            val data = buf.toByteArray()
            offsets.add(offset)
            offset += data.size
            chunks.add(data)
        }
        val dataSize = chunks.sumOf { it.size }
        val chunkSize = stringsStart + dataSize

        val out = ByteArrayOutputStream()
        out.write(u16le(0x0001))
        out.write(u16le(headerSize))
        out.write(u32le(chunkSize.toLong()))
        out.write(u32le(strings.size.toLong()))
        out.write(u32le(0))
        out.write(u32le(0))
        out.write(u32le(stringsStart.toLong()))
        out.write(u32le(0))
        for (o in offsets) out.write(u32le(o.toLong()))
        for (data in chunks) out.write(data)
        return out.toByteArray()
    }
}

fun buildMinimalAxml(): ByteArray {
    val sp = StringPoolBuilder()
    val pool = linkedMapOf<String, Int>()
    fun s(v: String): Int = pool.getOrPut(v) { sp.add(v) }

    val attrPackage = s("package")
    val attrVersionCode = s("versionCode")
    val attrVersionName = s("versionName")
    val attrMinSdk = s("minSdkVersion")
    val attrTargetSdk = s("targetSdkVersion")
    val nsAndroidStr = s("http://schemas.android.com/apk/res/android")
    val tagManifest = s("manifest")
    val tagUsesSdk = s("uses-sdk")
    val tagApplication = s("application")
    val pkgName = s("com.pickle.patcher.test")
    val versionName = s("1.0.0")

    val stringPool = sp.build()

    val resMap = ByteArrayOutputStream()
    resMap.write(u16(0x0180))
    resMap.write(u16(8))
    resMap.write(u32((8 + 5 * 4).toLong()))
    resMap.write(u32(0x0101021bL))
    resMap.write(u32(0x0101021cL))
    resMap.write(u32(0x0101021dL))
    resMap.write(u32(0x0101020cL))
    resMap.write(u32(0x01010270L))

    val nodes = ByteArrayOutputStream()
    fun startNamespace() {
        nodes.write(u16(0x0100))
        nodes.write(u16(16))
        nodes.write(u32(24))
        nodes.write(u32(1))
        nodes.write(u32(0xFFFFFFFF))
        nodes.write(u32(s("android").toLong()))
        nodes.write(u32(nsAndroidStr.toLong()))
    }

    fun endNamespace() {
        nodes.write(u16(0x0110))
        nodes.write(u16(16))
        nodes.write(u32(24))
        nodes.write(u32(1))
        nodes.write(u32(0xFFFFFFFF))
        nodes.write(u32(s("android").toLong()))
        nodes.write(u32(nsAndroidStr.toLong()))
    }

    fun startElement(name: Int, attrs: List<Pair<Int, Int>>, typedAttrs: List<Pair<Int, Long>>) {
        nodes.write(u16(0x0102))
        nodes.write(u16(16))
        nodes.write(u32((16 + 20 + (attrs.size + typedAttrs.size) * 20).toLong()))
        nodes.write(u32(1))
        nodes.write(u32(0xFFFFFFFF))
        nodes.write(u32(0xFFFFFFFF))
        nodes.write(u32(name.toLong()))
        nodes.write(u16(0x0014))
        nodes.write(u16(0x0014))
        nodes.write(u16((attrs.size + typedAttrs.size)))
        nodes.write(u16(0))
        nodes.write(u16(0))
        nodes.write(u16(0))
        for ((nameIdx, valueIdx) in attrs) {
            nodes.write(u32(0xFFFFFFFF))
            nodes.write(u32(nameIdx.toLong()))
            nodes.write(u32(valueIdx.toLong()))
            nodes.write(u16(8))
            nodes.write(byteArrayOf(0, 0x03))
            nodes.write(u32(valueIdx.toLong()))
        }
        for ((nameIdx, value) in typedAttrs) {
            nodes.write(u32(nsAndroidStr.toLong()))
            nodes.write(u32(nameIdx.toLong()))
            nodes.write(u32(0xFFFFFFFF))
            nodes.write(u16(8))
            nodes.write(byteArrayOf(0, 0x10))
            nodes.write(u32(value))
        }
    }

    fun endElement(name: Int) {
        nodes.write(u16(0x0103))
        nodes.write(u16(16))
        nodes.write(u32(24))
        nodes.write(u32(1))
        nodes.write(u32(0xFFFFFFFF))
        nodes.write(u32(0xFFFFFFFF))
        nodes.write(u32(name.toLong()))
    }

    startNamespace()
    startElement(
        tagManifest,
        attrs = listOf(
            attrPackage to pkgName,
            attrVersionName to versionName,
        ),
        typedAttrs = listOf(
            attrVersionCode to 1,
        ),
    )
    startElement(
        tagUsesSdk,
        attrs = emptyList(),
        typedAttrs = listOf(
            attrMinSdk to 24,
            attrTargetSdk to 24,
        ),
    )
    endElement(tagUsesSdk)
    startElement(tagApplication, attrs = emptyList(), typedAttrs = emptyList())
    endElement(tagApplication)
    endElement(tagManifest)
    endNamespace()

    val out = ByteArrayOutputStream()
    out.write(u16(0x0003))
    out.write(u16(8))
    out.write(u32((8 + stringPool.size + resMap.size() + nodes.size()).toLong()))
    out.write(stringPool)
    out.write(resMap.toByteArray())
    out.write(nodes.toByteArray())
    return out.toByteArray()
}
