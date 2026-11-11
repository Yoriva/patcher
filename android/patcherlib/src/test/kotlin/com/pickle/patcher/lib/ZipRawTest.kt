package com.pickle.patcher.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipRawTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @Test
    fun `open parses entries and contents roundtrip`() {
        val a = "hello world\n".toByteArray()
        val b = ByteArray(4096) { it.toByte() }
        val bytes = buildZip(listOf("a.txt" to a, "dir/b.bin" to b))
        val f = tmp.newFile("test.zip")
        f.writeBytes(bytes)

        val zip = ZipRaw.open(f)
        assertNotNull(zip)
        assertEquals(setOf("a.txt", "dir/b.bin"), zip!!.entries.keys)
        assertArrayEquals(a, zip.readContent(zip.entries.getValue("a.txt")))
        assertArrayEquals(b, zip.readContent(zip.entries.getValue("dir/b.bin")))
        zip.close()
    }

    @Test
    fun `open returns null for garbage`() {
        val f = tmp.newFile("junk.bin")
        f.writeBytes(ByteArray(64) { 0x41 })
        assertNull(ZipRaw.open(f))
    }

    @Test
    fun `open returns null for tiny file`() {
        val f = tmp.newFile("tiny.bin")
        f.writeBytes(byteArrayOf(1, 2))
        assertNull(ZipRaw.open(f))
    }

    @Test
    fun `stored entries are recognized with method 0`() {
        val content = "stored-content".toByteArray()
        val bytes = buildZip(
            listOf("resources.arsc" to content),
            storedEntries = setOf("resources.arsc"),
        )
        val f = tmp.newFile("stored.zip")
        f.writeBytes(bytes)

        val zip = ZipRaw.open(f)!!
        assertEquals(0, zip.entries.getValue("resources.arsc").method)
        assertArrayEquals(content, zip.readContent(zip.entries.getValue("resources.arsc")))
        zip.close()
    }
}
