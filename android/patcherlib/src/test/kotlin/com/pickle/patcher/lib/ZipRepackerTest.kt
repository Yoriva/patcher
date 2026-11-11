package com.pickle.patcher.lib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipRepackerTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun srcApk(): java.io.File {
        val f = tmp.newFile("src.apk")
        f.writeBytes(fakeApk())
        return f
    }

    @Test
    fun `repack injects bundle entries and keeps source`() {
        val src = srcApk()
        val out = tmp.newFile("out.apk")
        val manifest = BundleManifest(
            version = "test",
            abi = "arm64-v8a",
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "libamxmodx",
                    target = "lib/arm64-v8a/libamxmodx.so",
                    method = BundleManifest.Compression.STORED,
                    required = true,
                    description = "",
                ),
            ),
        )
        val bundle = Bundle(manifest, mapOf("libamxmodx" to ByteArray(64) { 9 }), emptyMap())
        val result = ZipRepacker.repack(src, out, bundle, pruneAbiExcept = "arm64-v8a")

        assertEquals(listOf("lib/arm64-v8a/libamxmodx.so"), result.added)
        assertTrue(result.kept.contains("AndroidManifest.xml"))
        assertTrue("lib/arm64-v8a/libyapb.so" in result.kept)

        val zip = ZipRaw.open(out)!!
        assertTrue("lib/arm64-v8a/libyapb.so" in zip.entries)
        assertTrue("lib/armeabi-v7a/libyapb.so" !in zip.entries)
        val arsc = zip.entries.getValue("resources.arsc")
        assertEquals(0, arsc.method)
        assertEquals(0, (arsc.dataOffset % 4).toInt())
        assertArrayEquals(ByteArray(64) { 9 }, zip.readContent(zip.entries.getValue("lib/arm64-v8a/libamxmodx.so")))
        zip.close()
    }

    @Test
    fun `repack strips meta inf via default exclude`() {
        val src = srcApk()
        val out = tmp.newFile("out.apk")
        val manifest = BundleManifest(entries = emptyList())
        val bundle = Bundle(manifest, emptyMap(), emptyMap())

        ZipRepacker.repack(src, out, bundle)

        val zip = ZipRaw.open(out)!!
        assertTrue("META-INF/MANIFEST.MF" !in zip.entries)
        assertTrue("AndroidManifest.xml" in zip.entries)
        zip.close()
    }

    @Test
    fun `libcs is patched when its bytes match the expected layout`() {
        val src = srcApk()
        val out = tmp.newFile("out.apk")
        val size = 0x247d28 + 4
        val libcs = ByteArray(size)
        val patches = listOf(
            0x240be4 to 20, 0x240c74 to 20, 0x240d04 to 20, 0x240d94 to 20,
            0x240e24 to 20, 0x240eb4 to 20, 0x240fc8 to 8, 0x247bf8 to 0,
            0x247c10 to 0, 0x247c28 to 0, 0x247c40 to 0, 0x247c58 to 0,
            0x247c70 to 0, 0x247d20 to 0, 0x236a84 to 8, 0x238500 to 8,
            0x236eb0 to 8, 0x23c240 to 8, 0x23d2d0 to 8, 0x23d75c to 9,
            0x241354 to 8,
        )
        for ((off, rt) in patches) {
            libcs[off] = rt.toByte()
            libcs[off + 3] = 0xB4.toByte()
        }
        val manifest = BundleManifest(
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "libcs",
                    target = "lib/arm64-v8a/libcs_android_arm64.so",
                    method = BundleManifest.Compression.DEFLATED,
                    required = true,
                    description = "",
                ),
            ),
        )
        val bundle = Bundle(manifest, mapOf("libcs" to libcs), emptyMap())
        val result = ZipRepacker.repack(src, out, bundle)

        assertTrue(result.patchedLibs.contains("lib/arm64-v8a/libcs_android_arm64.so"))
        val zip = ZipRaw.open(out)!!
        val patched = zip.readContent(zip.entries.getValue("lib/arm64-v8a/libcs_android_arm64.so"))
        for ((off, _) in patches) {
            assertEquals(0xB6.toByte(), patched[off + 3])
        }
        zip.close()
    }

    @Test
    fun `libcs is left alone when bytes do not match`() {
        val src = srcApk()
        val out = tmp.newFile("out.apk")
        val libcs = ByteArray(1024) { 0xAA.toByte() }
        val manifest = BundleManifest(
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "libcs",
                    target = "lib/arm64-v8a/libcs_android_arm64.so",
                    method = BundleManifest.Compression.DEFLATED,
                    required = true,
                    description = "",
                ),
            ),
        )
        val bundle = Bundle(manifest, mapOf("libcs" to libcs), emptyMap())
        val result = ZipRepacker.repack(src, out, bundle)

        assertTrue(result.patchedLibs.isEmpty())
        val zip = ZipRaw.open(out)!!
        assertArrayEquals(libcs, zip.readContent(zip.entries.getValue("lib/arm64-v8a/libcs_android_arm64.so")))
        zip.close()
    }
}
