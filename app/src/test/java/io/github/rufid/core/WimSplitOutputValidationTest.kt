package io.github.rufid.core

import io.github.rufid.windows.WindowsIsoPlan
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WimSplitOutputValidationTest {
    @Test
    fun partSizeMustBePositiveAndWithinFat32Limit() {
        assertThrows(IllegalArgumentException::class.java) {
            WimSplitOutputValidation.requireValidPartSizeBytes(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WimSplitOutputValidation.requireValidPartSizeBytes(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WimSplitOutputValidation.requireValidPartSizeBytes(WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1)
        }
        WimSplitOutputValidation.requireValidPartSizeBytes(1L)
        WimSplitOutputValidation.requireValidPartSizeBytes(WindowsIsoPlan.FAT32_MAX_FILE_SIZE)
    }

    @Test
    fun splitPartNumbersMatchWimlibNaming() {
        assertEquals(1, WimSplitOutputValidation.splitPartNumber("install.swm"))
        assertEquals(2, WimSplitOutputValidation.splitPartNumber("install2.swm"))
        assertEquals(3, WimSplitOutputValidation.splitPartNumber("INSTALL3.SWM"))
        assertEquals("install.swm", WimSplitOutputValidation.expectedPartFileName(1))
        assertEquals("install2.swm", WimSplitOutputValidation.expectedPartFileName(2))
    }

    @Test
    fun acceptsContinuousNonEmptyPartsAndIgnoresInstallWimSibling() {
        val dir = createTempDirectory("rufid-swm-ok").toFile()
        try {
            val installWim = File(dir, "install.wim").also { it.writeBytes(ByteArray(8) { 1 }) }
            val p1 = File(dir, "install.swm").also { it.writeBytes(ByteArray(4) { 2 }) }
            val p2 = File(dir, "install2.swm").also { it.writeBytes(ByteArray(5) { 3 }) }
            val validated = WimSplitOutputValidation.validateSplitPartFiles(dir.listFiles()!!.toList())
            assertEquals(listOf(p1, p2), validated)
            assertTrue(installWim.isFile)
            assertTrue(validated.none { it.name.equals("install.wim", ignoreCase = true) })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsGapEmptyOrOversizedParts() {
        val dir = createTempDirectory("rufid-swm-bad").toFile()
        try {
            File(dir, "install.swm").writeBytes(ByteArray(2))
            File(dir, "install3.swm").writeBytes(ByteArray(2))
            assertThrows(IllegalArgumentException::class.java) {
                WimSplitOutputValidation.validateSplitPartFiles(dir.listFiles()!!.toList())
            }

            dir.listFiles()?.forEach { it.delete() }
            File(dir, "install.swm").writeBytes(ByteArray(0))
            assertThrows(IllegalArgumentException::class.java) {
                WimSplitOutputValidation.validateSplitPartFiles(dir.listFiles()!!.toList())
            }

            dir.listFiles()?.forEach { it.delete() }
            File(dir, "install.swm").writeBytes(ByteArray(4))
            val huge = File(dir, "install2.swm")
            // Avoid allocating 4GiB: fake oversize by validating with tiny maxPartBytes.
            huge.writeBytes(ByteArray(8))
            assertThrows(IllegalArgumentException::class.java) {
                WimSplitOutputValidation.validateSplitPartFiles(
                    directoryFiles = dir.listFiles()!!.toList(),
                    maxPartBytes = 4L,
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun strategyCleansTempDirWhenEngineFails() {
        val cache = createTempDirectory("rufid-wim-cache").toFile()
        val strategy = CacheBackedWimSplitStrategy(
            cacheRoot = cache,
            engine = object : WimSplitEngine {
                override fun split(
                    wimFile: File,
                    firstSwmFile: File,
                    partSizeBytes: Long,
                    cancellationToken: CancellationToken,
                ) {
                    throw IOException("native boom")
                }
            },
            partSizeBytes = 1024L * 1024L,
        )
        try {
            assertThrows(IOException::class.java) {
                strategy.splitInstallWim(
                    ExtractableIsoFile(
                        path = "sources/install.wim",
                        size = 4,
                        reader = { fileOffset, buffer, offset, length ->
                            if (fileOffset >= 4) -1 else {
                                buffer.fill(9, offset, offset + length)
                                minOf(length, (4 - fileOffset).toInt())
                            }
                        },
                    ),
                )
            }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally {
            strategy.cleanup()
            cache.deleteRecursively()
        }
    }
}
