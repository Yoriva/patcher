package com.pickle.patcher.lib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @Test
    fun `fromZip parses manifest and resolves entries`() {
        val manifest = BundleManifest(
            version = "test",
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "assets/a.txt",
                    target = "assets/a.txt",
                    method = BundleManifest.Compression.DEFLATED,
                    required = true,
                    description = "",
                ),
            ),
        )
        val zipBytes = buildZip(
            listOf(
                "assets/a.txt" to "hello".toByteArray(),
                "bundle.json" to BundleManifest.encode(manifest).toByteArray(),
            ),
        )
        val bundle = Bundle.fromZip(zipBytes)
        assertEquals("test", bundle.manifest.version)
        assertEquals(1, bundle.files.size)
        assertArrayEquals("hello".toByteArray(), bundle.resolveEntry(bundle.manifest.entries[0]))
    }

    @Test
    fun `diskFiles are read from disk`() {
        val f = tmp.newFile("disk.so")
        f.writeBytes(byteArrayOf(1, 2, 3))
        val manifest = BundleManifest(
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "lib/x.so",
                    target = "lib/x.so",
                    method = BundleManifest.Compression.STORED,
                    required = false,
                    description = "",
                ),
            ),
        )
        val bundle = Bundle(manifest, emptyMap(), mapOf("lib/x.so" to f))
        assertArrayEquals(byteArrayOf(1, 2, 3), bundle.resolveEntry(manifest.entries[0]))
    }

    @Test
    fun `withEntries filters by target`() {
        val manifest = BundleManifest(
            entries = listOf(
                BundleManifest.BundleEntry("a", "a", BundleManifest.Compression.STORED, true, ""),
                BundleManifest.BundleEntry("b", "b", BundleManifest.Compression.STORED, true, ""),
            ),
        )
        val bundle = Bundle(manifest, emptyMap(), emptyMap())
        val filtered = bundle.withEntries(manifest.entries.filter { it.target == "a" })
        assertEquals(1, filtered.manifest.entries.size)
        assertEquals("a", filtered.manifest.entries[0].target)
    }

    @Test
    fun `fromZip throws on non-zip input`() {
        try {
            Bundle.fromZip(ByteArray(64) { 0x41 })
            throw AssertionError("expected failure")
        } catch (t: Throwable) {
            assertTrue(t is IllegalArgumentException || t is java.io.IOException || t is IllegalStateException)
        }
    }
}
