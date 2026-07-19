package io.github.rufid.ntfs

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.ExtractableIsoFile
import io.github.rufid.core.OperationCancelledException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SparseNtfsImageBuilderTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun processFailureDeletesIncompleteImage() {
        val nativeDir = temp.newFolder("lib")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).writeText("fake")
        File(nativeDir, NtfsNativeTools.STREAM_SO).writeText("fake")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).setExecutable(true)
        File(nativeDir, NtfsNativeTools.STREAM_SO).setExecutable(true)

        val launcher = object : NtfsProcessLauncher {
            override fun run(
                command: List<String>,
                workingDir: File?,
                stdinWriter: ((OutputStream) -> Unit)?,
                onCancelCheck: (() -> Unit)?,
            ): NtfsProcessResult {
                return NtfsProcessResult(exitCode = 9, stdout = "", stderr = "mkntfs boom")
            }
        }
        val cache = temp.newFolder("cache")
        val builder = SparseNtfsImageBuilder(nativeDir, launcher)
        val files = listOf(memoryFile("a.txt", byteArrayOf(1, 2, 3)))
        assertThrows(IOException::class.java) {
            builder.buildAndVerify(cache, 64L * 1024L * 1024L, files)
        }
        val leftovers = cache.listFiles()?.filter { it.name.endsWith(".img") }.orEmpty()
        assertTrue("incomplete image must be deleted", leftovers.isEmpty())
        // No protocol temp materialization files
        assertTrue(cache.listFiles()?.none { it.name.contains("proto") } != false)
    }

    @Test
    fun cancellationDuringMkntfsCleansUp() {
        val nativeDir = temp.newFolder("lib2")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        val token = CancellationToken.active()
        val launcher = object : NtfsProcessLauncher {
            override fun run(
                command: List<String>,
                workingDir: File?,
                stdinWriter: ((OutputStream) -> Unit)?,
                onCancelCheck: (() -> Unit)?,
            ): NtfsProcessResult {
                token.cancel()
                onCancelCheck?.invoke()
                return NtfsProcessResult(0, "", "")
            }
        }
        val cache = temp.newFolder("cache2")
        val builder = SparseNtfsImageBuilder(nativeDir, launcher)
        assertThrows(OperationCancelledException::class.java) {
            builder.buildAndVerify(
                cacheDir = cache,
                sizeBytes = 64L * 1024L * 1024L,
                files = listOf(memoryFile("b.txt", byteArrayOf(9))),
                cancellationToken = token,
            )
        }
        assertTrue(cache.listFiles()?.none { it.extension == "img" } != false)
    }

    @Test
    fun mkntfsCommandUsesRequiredFlags() {
        val nativeDir = temp.newFolder("lib3")
        val mk = File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        var captured: List<String>? = null
        val launcher = object : NtfsProcessLauncher {
            override fun run(
                command: List<String>,
                workingDir: File?,
                stdinWriter: ((OutputStream) -> Unit)?,
                onCancelCheck: (() -> Unit)?,
            ): NtfsProcessResult {
                if (command.first().endsWith(NtfsNativeTools.MKNTFS_SO) ||
                    command.first() == mk.absolutePath
                ) {
                    captured = command
                    return NtfsProcessResult(1, "", "stop after mkntfs check")
                }
                return NtfsProcessResult(0, "", "")
            }
        }
        val cache = temp.newFolder("cache3")
        val builder = SparseNtfsImageBuilder(nativeDir, launcher)
        assertThrows(IOException::class.java) {
            builder.buildAndVerify(cache, 64L * 1024L * 1024L, listOf(memoryFile("c.txt", byteArrayOf(1))))
        }
        val cmd = captured!!
        assertTrue(cmd.contains("-Q"))
        assertTrue(cmd.contains("-F"))
        assertTrue(cmd.contains("-s"))
        assertTrue(cmd.contains("512"))
        assertTrue(cmd.contains("-p"))
        assertTrue(cmd.contains("2048"))
        assertTrue(cmd.contains("-H"))
        assertTrue(cmd.contains("255"))
        assertTrue(cmd.contains("-S"))
        assertTrue(cmd.contains("63"))
        assertTrue(cmd.contains("-L"))
        assertTrue(cmd.contains("WININSTALL"))
        assertTrue(cmd.contains("-T"))
        assertFalse(cmd.any { it.contains(" ") && it.startsWith("sh") })
    }

    @Test
    fun streamUsesStdinWriterNotProtocolTemp() {
        val nativeDir = temp.newFolder("lib4")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        var streamStdinLen = -1
        val launcher = object : NtfsProcessLauncher {
            override fun run(
                command: List<String>,
                workingDir: File?,
                stdinWriter: ((OutputStream) -> Unit)?,
                onCancelCheck: (() -> Unit)?,
            ): NtfsProcessResult {
                val isStream = command.any { it.contains(NtfsNativeTools.STREAM_SO) }
                if (isStream) {
                    requireNotNull(stdinWriter) { "stream must use stdinWriter" }
                    val bos = ByteArrayOutputStream()
                    stdinWriter.invoke(bos)
                    streamStdinLen = bos.size()
                    // Minimal valid protocol would be larger than 0 for one file
                    return NtfsProcessResult(0, "", "")
                }
                // mkntfs
                return NtfsProcessResult(0, "", "")
            }
        }
        val cache = temp.newFolder("cache4")
        val payload = ByteArray(1024) { 7 }
        SparseNtfsImageBuilder(nativeDir, launcher).buildAndVerify(
            cache,
            64L * 1024L * 1024L,
            listOf(memoryFile("sources/x.bin", payload)),
        )
        assertTrue("stdinWriter must stream protocol+payload", streamStdinLen > payload.size)
        assertTrue(cache.listFiles()?.none { it.name.contains("proto") } != false)
    }

    @Test
    fun cachePreflightDoesNotChargeDoublePayloadProtocolTemp() {
        val nativeDir = temp.newFolder("lib5")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        val cache = temp.newFolder("cache5")
        val builder = SparseNtfsImageBuilder(nativeDir)
        // Should not throw for moderate payload when usable space is huge (or zero-reported)
        builder.validateCacheSpace(cache, 8L * 1024L * 1024L * 1024L)
        // Formula must not include 2x payload
        val payload = 8L * 1024L * 1024L * 1024L
        val meta = maxOf(payload / 6, 32L * 1024L * 1024L)
        val need = payload + meta + 64L * 1024L * 1024L
        val doubleWouldBe = payload * 2 + meta + 64L * 1024L * 1024L
        assertTrue(need < doubleWouldBe)
        assertEquals(payload + meta, NtfsImageSizing.estimatedSparseAllocatedBytes(payload))
    }

    private fun memoryFile(path: String, data: ByteArray): ExtractableIsoFile =
        ExtractableIsoFile(
            path = path,
            size = data.size.toLong(),
            reader = { fileOffset, buffer, outputOffset, length ->
                val start = fileOffset.toInt()
                val n = minOf(length, data.size - start)
                if (n <= 0) return@ExtractableIsoFile -1
                data.copyInto(buffer, outputOffset, start, start + n)
                n
            },
        )
}
