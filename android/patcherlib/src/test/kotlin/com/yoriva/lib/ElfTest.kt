package com.yoriva.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ElfTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun elfBytes(
        is64: Boolean = true,
        type: Int = 3,
        machine: Int = ElfInfo.EM_AARCH64,
        littleEndian: Boolean = true,
    ): ByteArray {
        val b = ByteArray(64)
        b[0] = 0x7F.toByte()
        b[1] = 'E'.toByte()
        b[2] = 'L'.toByte()
        b[3] = 'F'.toByte()
        b[4] = if (is64) 2 else 1
        b[5] = if (littleEndian) 1 else 2
        b[6] = 1
        fun u16(at: Int, v: Int) {
            b[at] = (v and 0xFF).toByte()
            b[at + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        u16(16, type)
        u16(18, machine)
        return b
    }

    @Test
    fun `readElfInfo parses a 64-bit AArch64 shared object`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(elfBytes())
        val info = readElfInfo(f)!!
        assertTrue(info.is64Bit)
        assertTrue(info.littleEndian)
        assertTrue(info.isSharedObject)
        assertEquals(ElfInfo.EM_AARCH64, info.machine)
    }

    @Test
    fun `readElfInfo rejects non-elf bytes`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(ByteArray(64) { 1 })
        assertNull(readElfInfo(f))
    }

    @Test
    fun `validateElf rejects big-endian files`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(elfBytes(littleEndian = false))
        assertTrue(validateElf(f, "arm64-v8a") is ElfVerdict.Rejected)
    }

    @Test
    fun `validateElf accepts matching abi`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(elfBytes(machine = ElfInfo.EM_AARCH64))
        assertEquals(ElfVerdict.Ok, validateElf(f, "arm64-v8a"))
    }

    @Test
    fun `validateElf rejects wrong machine`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(elfBytes(machine = ElfInfo.EM_ARM))
        assertTrue(validateElf(f, "arm64-v8a") is ElfVerdict.Rejected)
    }

    @Test
    fun `validateElf rejects executables`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(elfBytes(type = 2))
        assertTrue(validateElf(f, "arm64-v8a") is ElfVerdict.Rejected)
    }

    @Test
    fun `validateElf rejects unknown abi`() {
        val f = tmp.newFile("lib.so")
        f.writeBytes(elfBytes())
        assertTrue(validateElf(f, "mips") is ElfVerdict.Rejected)
    }
}
