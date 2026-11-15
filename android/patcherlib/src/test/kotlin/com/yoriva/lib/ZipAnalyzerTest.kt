package com.yoriva.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipAnalyzerTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @Test
    fun `detect abis from lib paths`() {
        val zipBytes = buildZip(
            listOf(
                "lib/arm64-v8a/libfoo.so" to byteArrayOf(
                    0x7F.toByte(), 'E'.toByte(), 'L'.toByte(), 'F'.toByte(),
                    2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0xB7.toByte(), 0,
                ),
                "lib/armeabi-v7a/libbar.so" to byteArrayOf(
                    0x7F.toByte(), 'E'.toByte(), 'L'.toByte(), 'F'.toByte(),
                    1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 40, 0,
                ),
            ),
        )
        val apk = tmp.newFile("app.apk")
        apk.writeBytes(zipBytes)

        val info = ZipAnalyzer.analyze(apk)
        assertTrue("arm64-v8a" in info.abis)
        assertTrue("armeabi-v7a" in info.abis)
        assertEquals(2, info.libCount)
        assertEquals(2, info.entryCount)
    }

    @Test
    fun `analyzes an apk without libs`() {
        val apk = tmp.newFile("nolib.apk")
        apk.writeBytes(buildZip(listOf("AndroidManifest.xml" to "x".toByteArray())))

        val info = ZipAnalyzer.analyze(apk)
        assertTrue(info.abis.isEmpty())
        assertEquals(0, info.libCount)
        assertEquals(1, info.entryCount)
    }

    @Test
    fun `entryState reports presence and offset`() {
        val content = "abc".toByteArray()
        val apk = tmp.newFile("app.apk")
        apk.writeBytes(buildZip(listOf("hello.txt" to content)))

        val (exists, method, offset) = ZipAnalyzer.entryState(apk, "hello.txt")
        assertTrue(exists)
        assertTrue(method == 8 || method == 0)
        assertTrue(offset > 0)

        val (missing, m2, _) = ZipAnalyzer.entryState(apk, "nope")
        assertFalse(missing)
        assertEquals(-1, m2)
    }
}
