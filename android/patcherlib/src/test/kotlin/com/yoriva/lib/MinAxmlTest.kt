package com.yoriva.lib

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MinAxmlTest {

    @Test
    fun dumpMinimalAxml() {
        val out = Files.createTempFile("minimal-axml", ".bin")
        Files.write(out, buildMinimalAxml())
        println("AXML-DUMP: $out (${Files.size(out)} bytes)")
        assertTrue(Files.size(out) > 0)
    }
}
