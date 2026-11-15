package com.yoriva.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleManifestTest {

    private val sample = """
        {
          "version": "1.0.0",
          "game": "cs16client",
          "abi": "arm64-v8a",
          "entries": [
            {
              "source": "libs/libamx.so",
              "target": "lib/arm64-v8a/libamxmodx.so",
              "method": "STORED",
              "required": true,
              "description": "amx"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse roundtrip preserves data`() {
        val m = BundleManifest.parse(sample)
        assertEquals("1.0.0", m.version)
        assertEquals(1, m.entries.size)
        assertEquals("lib/arm64-v8a/libamxmodx.so", m.entries[0].target)
        assertEquals(BundleManifest.Compression.STORED, m.entries[0].method)
        assertEquals(m, BundleManifest.parse(BundleManifest.encode(m)))
    }

    @Test
    fun `unknown keys are ignored`() {
        val withExtra = """{"version":"1.0.0","entries":[],"futureField":42}"""
        val m = BundleManifest.parse(withExtra)
        assertEquals("1.0.0", m.version)
        assertTrue(m.entries.isEmpty())
    }

    @Test
    fun `defaults are sane`() {
        val m = BundleManifest.parse("{}")
        assertEquals("", m.version)
        assertEquals("cs16client", m.game)
        assertTrue(m.entries.isEmpty())
    }
}
