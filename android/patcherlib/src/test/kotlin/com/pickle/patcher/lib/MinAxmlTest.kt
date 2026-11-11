package com.pickle.patcher.lib

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class MinAxmlTest {

    @Test
    fun dumpMinimalAxml() {
        val out = Files.createTempFile("minimal-axml", ".bin")
        Files.write(out, buildMinimalAxml())
        println("AXML-DUMP: $out (${Files.size(out)} bytes)")
        assertTrue(Files.size(out) > 0)
    }
}
