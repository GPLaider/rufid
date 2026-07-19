package io.github.rufid.ntfs

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfsStreamProtocolTest {
    @Test
    fun acceptsSafeRelativePaths() {
        NtfsStreamProtocol.requireSafeRelativePath("sources/install.wim")
        NtfsStreamProtocol.requireSafeRelativePath("a")
        // Unicode via escapes only: lu4 jing4 = \u8def\u5f84
        NtfsStreamProtocol.requireSafeRelativePath("nested/long/unicode-\u8def\u5f84/file.txt")
        // Must not require pre-sanitization; reject original unsafe forms separately.
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("nested\\long\\file.txt")
        }
    }

    @Test
    fun rejectsAbsoluteBackslashControlEmptyDot() {
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("/etc/passwd")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a\\b")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a\u0000b")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a/../b")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a/./b")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a//b")
        }
    }

    @Test
    fun rejectsWindowsForbiddenComponents() {
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a:b")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a*b")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("foo.")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("foo ")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("CON")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("sources/nul.txt")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("COM1")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("LPT9.dat")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("a\u007fb")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("dir./file")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("dir /file")
        }
    }

    @Test
    fun rejectsOversizedComponentAndFileSize() {
        val longSeg = "a".repeat(NtfsStreamProtocol.MAX_UCS_NAME + 1)
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath(longSeg)
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeFileSize(-1L)
        }
    }

    @Test
    fun encodeSessionWireFormat() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = NtfsStreamProtocol.encodeSession(
            mode = NtfsStreamProtocol.MODE_POPULATE,
            dirs = listOf("sources"),
            files = listOf("sources/a.bin" to payload),
        )
        val input = ByteArrayInputStream(bytes)
        assertEquals(NtfsStreamProtocol.MODE_POPULATE, input.read())
        assertEquals(NtfsStreamProtocol.KIND_DIR, input.read())
        val dirLen = NtfsStreamProtocol.readU16Le(input)
        assertEquals(7, dirLen)
        val dir = ByteArray(dirLen)
        assertEquals(dirLen, input.read(dir))
        assertEquals("sources", String(dir, Charsets.UTF_8))
        assertEquals(NtfsStreamProtocol.KIND_FILE, input.read())
        val pathLen = NtfsStreamProtocol.readU16Le(input)
        val path = ByteArray(pathLen)
        assertEquals(pathLen, input.read(path))
        assertEquals("sources/a.bin", String(path, Charsets.UTF_8))
        val size = NtfsStreamProtocol.readU64Le(input)
        assertEquals(5L, size)
        val body = ByteArray(5)
        assertEquals(5, input.read(body))
        assertArrayEquals(payload, body)
        assertEquals(NtfsStreamProtocol.KIND_END, input.read())
        assertEquals(-1, input.read())
    }

    @Test
    fun oversizedPathRejected() {
        val long = "a".repeat(NtfsStreamProtocol.MAX_PATH_BYTES + 1)
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath(long)
        }
    }

    @Test
    fun le64RoundTrip() {
        val bos = java.io.ByteArrayOutputStream()
        NtfsStreamProtocol.writeU64Le(bos, 0x0102030405060708L)
        val input = ByteArrayInputStream(bos.toByteArray())
        assertEquals(0x0102030405060708L, NtfsStreamProtocol.readU64Le(input))
        assertTrue(true)
    }
}
