package com.yoriva.lib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleBuilderTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writeBundleZip roundtrips through fromZip`() {
        val manifest = BundleManifest(
            version = "v1",
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "x.txt",
                    target = "x.txt",
                    method = BundleManifest.Compression.DEFLATED,
                    required = true,
                    description = "",
                ),
            ),
        )
        val bundle = Bundle(manifest, mapOf("x.txt" to "data".toByteArray()), emptyMap())
        val out = tmp.newFile("bundle.zip")
        BundleBuilder.writeBundleZip(bundle, out)

        val reloaded = Bundle.fromZip(out.readBytes())
        assertEquals("v1", reloaded.manifest.version)
        assertArrayEquals("data".toByteArray(), reloaded.resolveEntry(reloaded.manifest.entries[0]))
    }

    @Test
    fun `build collects entries from disk`() {
        val a = tmp.newFile("a.so")
        a.writeBytes(byteArrayOf(1, 2, 3))
        val built = BundleBuilder.build(
            entries = listOf("lib/a.so" to a),
            manifest = BundleManifest(version = "v2", entries = emptyList()),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), built.files.getValue("lib/a.so"))
        assertTrue(built.diskFiles.isEmpty())
    }
}
