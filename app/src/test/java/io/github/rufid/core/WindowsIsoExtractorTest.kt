package io.github.rufid.core

import io.github.rufid.windows.WindowsIsoPlan
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsIsoExtractorTest {
    @Test
    fun extractsIso9660WindowsInstallerFilesToFat32UsbLayout() {
        val iso = MinimalIso9660Image()
            .file("bootmgr", byteArrayOf(0x42, 0x4f, 0x4f, 0x54))
            .file("efi/boot/bootx64.efi", byteArrayOf(0x45, 0x46, 0x49))
            .file("sources/install.wim", byteArrayOf(0x57, 0x49, 0x4d))
            .file("sources/appraiserres.dll", byteArrayOf(0x41, 0x50, 0x50))
            .build()
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)

        WindowsIsoExtractor(target).write(
            source = ByteArraySeekableByteSource(iso),
            imageName = "Win11_English_x64.iso",
        ) { }

        val fat = Fat32Image(target.bytes)
        assertTrue(fat.hasMbrFat32Partition())
        assertTrue(fat.rootShortNames().none { it == "." || it == ".." })
        assertEquals("WININSTALL", fat.rootVolumeLabel())
        assertEquals(fat.freeClusterCountFromFat(), fat.fsInfoFreeClusterCount())
        assertEquals(FAT32_EOC, fat.fatEntry(fat.firstCluster("EFI")))
        assertEquals(FAT32_EOC, fat.fatEntry(fat.firstCluster("SOURCES")))
        assertEquals(fat.firstCluster("EFI"), fat.dotCluster("EFI"))
        assertEquals(0, fat.dotDotCluster("EFI"))
        assertEquals(0, fat.dotDotCluster("SOURCES"))
        assertArrayEquals(byteArrayOf(0x42, 0x4f, 0x4f, 0x54), fat.readFile("BOOTMGR"))
        assertArrayEquals(byteArrayOf(0x45, 0x46, 0x49), fat.readFile("EFI/BOOT/BOOTX64.EFI"))
        assertArrayEquals(byteArrayOf(0x57, 0x49, 0x4d), fat.readFile("SOURCES/INSTALL.WIM"))
        assertArrayEquals(byteArrayOf(0x41, 0x50, 0x50), fat.readFile("SOURCES/APPRAISERRES.DLL"))
        assertTrue(target.flushed)
    }

    @Test
    fun batchesFat32MetadataWritesBeforeExtractingIsoFiles() {
        val iso = MinimalIso9660Image()
            .file("bootmgr", byteArrayOf(0x42))
            .file("efi/boot/bootx64.efi", byteArrayOf(0x45))
            .file("sources/install.wim", byteArrayOf(0x57, 0x49, 0x4d))
            .build()
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)

        WindowsIsoExtractor(target).write(
            source = ByteArraySeekableByteSource(iso),
            imageName = "Win11_English_x64.iso",
        ) { }

        assertTrue("expected batched metadata writes, got ${target.writeCalls}", target.writeCalls <= 128)
    }

    @Test
    fun streamsLargeFatTablesWithoutAllocatingWholeFat() {
        val target = CountingBlockDevice(sizeBytes = 60L * 1024L * 1024L * 1024L)

        WindowsIsoFileSetExtractor(target).write(
            files = listOf(
                extractable("bootmgr", byteArrayOf(0x42)),
                extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
                extractable("sources/install.wim", byteArrayOf(0x57, 0x49, 0x4d)),
            ),
            imageName = "Win11_English_x64.iso",
        ) { }

        assertTrue("largest write was ${target.maxWriteLength}", target.maxWriteLength <= 128 * 512)
        assertTrue("write calls were ${target.writeCalls}", target.writeCalls < 500_000)
        assertTrue(target.flushed)
    }

    @Test
    fun streamsLargeSplitWimClusterChainsWithoutKeepingPerClusterLists() {
        val target = CountingBlockDevice(sizeBytes = 60L * 1024L * 1024L * 1024L)

        WindowsIsoFileSetExtractor(
            blockDevice = target,
            wimSplitStrategy = object : WimSplitStrategy {
                override fun splitInstallWim(
                    source: ExtractableIsoFile,
                    cancellationToken: CancellationToken,
                ): List<ExtractableIsoFile> =
                    listOf(
                        largeExtractable("sources/install.swm", 3800L * 1024L * 1024L),
                        largeExtractable("sources/install2.swm", 3450L * 1024L * 1024L),
                    )
            },
        ).write(
            files = listOf(
                extractable("bootmgr", byteArrayOf(0x42)),
                extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
                largeExtractable("sources/install.wim", WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1),
            ),
            imageName = "Win11_English_x64.iso",
        ) { }

        assertTrue("largest write was ${target.maxWriteLength}", target.maxWriteLength <= 128 * 512)
        assertTrue("write calls were ${target.writeCalls}", target.writeCalls < 500_000)
        assertTrue(target.flushed)
    }

    @Test
    fun writesMultiClusterDirectoriesWithZeroOffsetBlockWrites() {
        val target = RejectingOffsetBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val files = mutableListOf(
            extractable("bootmgr", byteArrayOf(0x42)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
            extractable("sources/install.wim", byteArrayOf(0x57, 0x49, 0x4d)),
        )
        repeat(80) { index ->
            files += extractable("sources/driver-package-$index.inf", byteArrayOf(index.toByte()))
        }

        WindowsIsoFileSetExtractor(target).write(
            files = files,
            imageName = "Win11_English_x64.iso",
        ) { }

        assertTrue(target.flushed)
    }

    @Test
    fun writesSplitWimSetWhenStrategyIsAvailable() {
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val files = listOf(
            extractable("bootmgr", byteArrayOf(1)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(2)),
            ExtractableIsoFile(
                path = "sources/install.wim",
                size = 4L * 1024L * 1024L * 1024L,
                reader = { _, buffer, offset, _ ->
                    buffer[offset] = 3
                    1
                },
            ),
        )

        WindowsIsoFileSetExtractor(
            blockDevice = target,
            wimSplitStrategy = object : WimSplitStrategy {
                override fun splitInstallWim(
                    source: ExtractableIsoFile,
                    cancellationToken: CancellationToken,
                ): List<ExtractableIsoFile> =
                    listOf(
                        extractable("sources/install.swm", byteArrayOf(0x10, 0x11)),
                        extractable("sources/install2.swm", byteArrayOf(0x12)),
                    )
            },
        ).write(
            files = files,
            imageName = "Win11_English_x64.iso",
        ) { }

        val fat = Fat32Image(target.bytes)
        assertArrayEquals(byteArrayOf(1), fat.readFile("BOOTMGR"))
        assertArrayEquals(byteArrayOf(2), fat.readFile("EFI/BOOT/BOOTX64.EFI"))
        assertArrayEquals(byteArrayOf(0x10, 0x11), fat.readFile("SOURCES/INSTALL.SWM"))
        assertArrayEquals(byteArrayOf(0x12), fat.readFile("SOURCES/INSTALL2.SWM"))
        assertThrows(IOException::class.java) { fat.readFile("SOURCES/INSTALL.WIM") }
    }

    @Test
    fun extractsUdfWindowsInstallerAndSplitsLargeInstallWimWithoutFilenameHeuristic() {
        val iso = MinimalUdfImage(WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1).build()
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        var observedInstallWimSize = 0L

        WindowsIsoExtractor(
            blockDevice = target,
            wimSplitStrategy = object : WimSplitStrategy {
                override fun splitInstallWim(
                    source: ExtractableIsoFile,
                    cancellationToken: CancellationToken,
                ): List<ExtractableIsoFile> {
                    observedInstallWimSize = source.size
                    return listOf(extractable("sources/install.swm", byteArrayOf(0x33, 0x44)))
                }
            },
        ).write(
            source = ByteArraySeekableByteSource(iso),
            imageName = "neutral-name.iso",
        ) { }

        assertEquals(WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1, observedInstallWimSize)
        val fat = Fat32Image(target.bytes)
        assertArrayEquals(byteArrayOf(0x42), fat.readFile("BOOTMGR"))
        assertArrayEquals(byteArrayOf(0x45), fat.readFile("EFI/BOOT/BOOTX64.EFI"))
        assertArrayEquals(byteArrayOf(0x33, 0x44), fat.readFile("SOURCES/INSTALL.SWM"))
        assertThrows(IOException::class.java) { fat.readFile("SOURCES/INSTALL.WIM") }
    }

    @Test
    fun ignoresUdfFileIdentifiersWithZeroLengthIcb() {
        val iso = MinimalUdfImage(
            installWimSize = WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1,
            includeZeroLengthIcbEntry = true,
        ).build()

        val files = IsoImageReader.listFiles(ByteArraySeekableByteSource(iso))

        assertTrue(files.any { it.path.normalizeIsoPath() == "sources/install.wim" })
        assertTrue(files.none { it.path.normalizeIsoPath() == "placeholder" })
    }

    @Test
    fun refusesWindowsIsoWhenInstallWimNeedsSplitAndNoStrategyIsAvailable() {
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val files = listOf(
            extractable("bootmgr", byteArrayOf(1)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(2)),
            ExtractableIsoFile(
                path = "sources/install.wim",
                size = WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1,
                reader = { _, buffer, offset, _ ->
                    buffer[offset] = 3
                    1
                },
            ),
        )

        val error = assertThrows(UnsupportedOperationException::class.java) {
            WindowsIsoFileSetExtractor(target).write(
                files = files,
                imageName = "Win11_English_x64.iso",
            ) { }
        }

        assertTrue(error.message.orEmpty().contains("install.wim split required"))
    }

    @Test
    fun validatesEntireFat32TreeBeforeChangingUsb() {
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val files = listOf(
            extractable("bootmgr", byteArrayOf(1)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(2)),
            extractable("sources/install.wim", byteArrayOf(3)),
            extractable("/", byteArrayOf(4)),
        )

        assertThrows(RuntimeException::class.java) {
            WindowsIsoFileSetExtractor(target).write(
                files = files,
                imageName = "Win11_English_x64.iso",
            ) { }
        }

        assertEquals(0, target.writeCalls)
    }

    @Test
    fun rejectsOversizedExtractedTreeBeforeChangingUsb() {
        val target = CountingBlockDevice(sizeBytes = 64L * 1024L * 1024L)
        val files = listOf(
            extractable("bootmgr", byteArrayOf(1)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(2)),
            largeExtractable("sources/install.wim", 96L * 1024L * 1024L),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WindowsIsoFileSetExtractor(target).write(
                files = files,
                imageName = "Win11_English_x64.iso",
            ) { }
        }

        assertEquals(0, target.writeCalls)
    }

    @Test
    fun rejectsUnsafeFat32PathsBeforeChangingUsb() {
        val unsafePaths = listOf(
            "../bootmgr",
            "efi\\boot\\bootx64.efi",
            "sources/bad?.dll",
            "sources/trailing. ",
            "sources/${"a".repeat(256)}",
        )

        for (unsafePath in unsafePaths) {
            val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
            val files = listOf(
                extractable("bootmgr", byteArrayOf(1)),
                extractable("efi/boot/bootx64.efi", byteArrayOf(2)),
                extractable("sources/install.wim", byteArrayOf(3)),
                extractable(unsafePath, byteArrayOf(4)),
            )

            assertThrows(IllegalArgumentException::class.java) {
                WindowsIsoFileSetExtractor(target).write(files, "Win11.iso") { }
            }
            assertEquals("path=$unsafePath", 0, target.writeCalls)
        }
    }

    @Test
    fun rejectsFileDirectoryCollisionBeforeChangingUsb() {
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val files = listOf(
            extractable("bootmgr", byteArrayOf(1)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(2)),
            extractable("sources/install.wim", byteArrayOf(3)),
            extractable("drivers", byteArrayOf(4)),
            extractable("drivers/readme.txt", byteArrayOf(5)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WindowsIsoFileSetExtractor(target).write(files, "Win11.iso") { }
        }
        assertEquals(0, target.writeCalls)
    }

    @Test
    fun reportsMonotonicByteProgressPerClusterDuringCopy() {
        val payload = ByteArray(96 * 1024) { index -> (index % 251).toByte() }
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val events = mutableListOf<Progress>()
        val files = listOf(
            extractable("bootmgr", byteArrayOf(0x42)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
            extractable("sources/install.wim", payload),
        )
        val total = files.sumOf { it.size }

        WindowsIsoFileSetExtractor(target).write(
            files = files,
            imageName = "Win11_English_x64.iso",
        ) { events += it }

        val writing = events.filter { it.phase == Progress.Phase.Writing }
        assertTrue("expected multi-cluster progress, got ${writing.size}", writing.size >= 2)
        assertTrue(writing.all { it.bytesDone in 0L..it.bytesTotal })
        assertTrue(writing.all { it.bytesTotal == total })
        for (index in 1 until writing.size) {
            assertTrue(
                "progress not monotonic at $index: ${writing[index - 1].bytesDone} -> ${writing[index].bytesDone}",
                writing[index].bytesDone >= writing[index - 1].bytesDone,
            )
        }
        assertEquals(total, writing.last().bytesDone)
        val finished = events.filter { it.phase == Progress.Phase.Finished }
        assertEquals(1, finished.size)
        assertEquals(total, finished.single().bytesDone)
        assertEquals(total, finished.single().bytesTotal)
        assertTrue(target.flushed)
    }

    @Test
    fun cancelsBetweenClusterWritesWithoutFlushOrFinished() {
        val token = CancellationToken.active()
        val payload = ByteArray(128 * 1024) { 0x57 }
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val events = mutableListOf<Progress>()
        val files = listOf(
            extractable("bootmgr", byteArrayOf(0x42)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
            extractable("sources/install.wim", payload),
        )

        assertThrows(OperationCancelledException::class.java) {
            WindowsIsoFileSetExtractor(target).write(
                files = files,
                imageName = "Win11_English_x64.iso",
                cancellationToken = token,
            ) { progress ->
                events += progress
                if (progress.phase == Progress.Phase.Writing && progress.bytesDone > 0L) {
                    token.cancel()
                }
            }
        }

        assertTrue(events.any { it.phase == Progress.Phase.Writing })
        assertTrue(events.none { it.phase == Progress.Phase.Finished })
        assertFalse(target.flushed)
    }

    @Test
    fun earlyEofDuringCopyDoesNotFlushOrEmitFinished() {
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val events = mutableListOf<Progress>()
        val truncated = ExtractableIsoFile(
            path = "sources/install.wim",
            size = 16 * 1024L,
            reader = { fileOffset, buffer, offset, length ->
                if (fileOffset >= 512L) {
                    -1
                } else {
                    val count = minOf(length.toLong(), 512L - fileOffset).toInt()
                    buffer.fill(0x57, offset, offset + count)
                    count
                }
            },
        )
        val files = listOf(
            extractable("bootmgr", byteArrayOf(0x42)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
            truncated,
        )

        val error = assertThrows(IOException::class.java) {
            WindowsIsoFileSetExtractor(target).write(
                files = files,
                imageName = "Win11_English_x64.iso",
            ) { events += it }
        }

        assertTrue(error.message.orEmpty().contains("ended early"))
        assertTrue(events.none { it.phase == Progress.Phase.Finished })
        assertFalse(target.flushed)
    }

    @Test
    fun nestedDirectoryLongFileNameRoundTripOnMemoryImage() {
        val longName = "Very Long Package Description And Notes.txt"
        val content = "nested-lfn-payload".encodeToByteArray()
        val target = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        val files = listOf(
            extractable("bootmgr", byteArrayOf(0x42)),
            extractable("efi/boot/bootx64.efi", byteArrayOf(0x45)),
            extractable("sources/install.wim", byteArrayOf(0x57, 0x49, 0x4d)),
            extractable("sources/driver packages/$longName", content),
        )

        WindowsIsoFileSetExtractor(target).write(
            files = files,
            imageName = "Win11_English_x64.iso",
        ) { }

        val fat = Fat32Image(target.bytes)
        assertArrayEquals(content, fat.readFile("sources/driver packages/$longName"))
        assertEquals(fat.firstCluster("sources/driver packages"), fat.dotCluster("sources/driver packages"))
        assertEquals(fat.firstCluster("SOURCES"), fat.dotDotCluster("sources/driver packages"))
        assertTrue(target.flushed)
    }

    @Test
    fun cacheBackedWimSplitStopsCopyWhenCancelled() {
        val tempDir = Files.createTempDirectory("rufid-wim-cancel-test").toFile()
        val token = CancellationToken.active()
        var reads = 0
        val source = ExtractableIsoFile(
            path = "sources/install.wim",
            size = 3L * 1024L * 1024L,
            reader = { _, buffer, offset, length ->
                reads++
                buffer.fill(0x57, offset, offset + length)
                token.cancel()
                length
            },
        )
        val strategy = CacheBackedWimSplitStrategy(
            cacheRoot = tempDir,
            engine = object : WimSplitEngine {
                override fun split(
                    wimFile: java.io.File,
                    firstSwmFile: java.io.File,
                    partSizeBytes: Long,
                    cancellationToken: CancellationToken,
                ) {
                    throw AssertionError("split must not start after cancellation")
                }
            },
        )

        try {
            assertThrows(OperationCancelledException::class.java) {
                strategy.splitInstallWim(source, token)
            }
            assertEquals(1, reads)
        } finally {
            strategy.cleanup()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cacheBackedWimSplitStrategyMaterializesSourceAndReturnsSplitParts() {
        val tempDir = Files.createTempDirectory("rufid-wim-split-test").toFile()
        val sourceBytes = byteArrayOf(0x57, 0x49, 0x4d)
        var receivedWimBytes: ByteArray? = null
        var receivedPartSize = 0L
        val strategy = CacheBackedWimSplitStrategy(
            cacheRoot = tempDir,
            engine = object : WimSplitEngine {
                override fun split(
                    wimFile: java.io.File,
                    firstSwmFile: java.io.File,
                    partSizeBytes: Long,
                    cancellationToken: CancellationToken,
                ) {
                    receivedWimBytes = wimFile.readBytes()
                    receivedPartSize = partSizeBytes
                    firstSwmFile.writeBytes(byteArrayOf(0x01, 0x02))
                    firstSwmFile.resolveSibling("install2.swm").writeBytes(byteArrayOf(0x03))
                }
            },
            partSizeBytes = 4L * 1024L * 1024L,
        )
        var splitFiles: List<ExtractableIsoFile> = emptyList()

        try {
            splitFiles = strategy.splitInstallWim(extractable("sources/install.wim", sourceBytes))

            assertArrayEquals(sourceBytes, receivedWimBytes)
            assertEquals(4L * 1024L * 1024L, receivedPartSize)
            assertEquals(listOf("sources/install.swm", "sources/install2.swm"), splitFiles.map { it.path })
            assertArrayEquals(byteArrayOf(0x01, 0x02), readExtractable(splitFiles[0]))
            assertArrayEquals(byteArrayOf(0x03), readExtractable(splitFiles[1]))
        } finally {
            splitFiles.forEach { it.close() }
            strategy.cleanup()
        }
        assertTrue(tempDir.list().orEmpty().isEmpty())
        assertTrue(tempDir.delete())
    }

    private fun extractable(path: String, content: ByteArray): ExtractableIsoFile =
        ExtractableIsoFile(
            path = path,
            size = content.size.toLong(),
            reader = { fileOffset, buffer, offset, length ->
                if (fileOffset >= content.size) {
                    -1
                } else {
                    val count = minOf(length, content.size - fileOffset.toInt())
                    content.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
                    count
                }
            },
        )

    private fun largeExtractable(path: String, size: Long): ExtractableIsoFile =
        ExtractableIsoFile(
            path = path,
            size = size,
            reader = { fileOffset, _, _, length ->
                if (fileOffset >= size) {
                    -1
                } else {
                    minOf(length.toLong(), size - fileOffset).toInt()
                }
            },
        )

    private fun readExtractable(file: ExtractableIsoFile): ByteArray {
        val result = ByteArray(file.size.toInt())
        var offset = 0
        while (offset < result.size) {
            val read = file.readAt(offset.toLong(), result, offset, result.size - offset)
            if (read <= 0) throw IOException("Extractable ended early: ${file.path}")
            offset += read
        }
        return result
    }

    private class MemoryBlockDevice(
        override val blockSize: Int = 512,
        override val sizeBytes: Long,
    ) : SeekableBlockDevice {
        val bytes = ByteArray(sizeBytes.toInt())
        var flushed = false
        var writeCalls = 0
            private set
        private var position = 0

        override fun seek(byteOffset: Long) {
            position = byteOffset.toInt()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            bytes.copyInto(buffer, offset, position, position + length)
            position += length
            return length
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeCalls++
            buffer.copyInto(bytes, position, offset, offset + length)
            position += length
        }

        override fun flush() {
            flushed = true
        }

        override fun close() = Unit
    }

    private class CountingBlockDevice(
        override val blockSize: Int = 512,
        override val sizeBytes: Long,
    ) : SeekableBlockDevice {
        var maxWriteLength = 0
            private set
        var writeCalls = 0
            private set
        var flushed = false
            private set
        private var position = 0L

        override fun seek(byteOffset: Long) {
            position = byteOffset
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throw IOException("CountingBlockDevice does not support reads")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeCalls++
            maxWriteLength = maxOf(maxWriteLength, length)
            position += length
        }

        override fun flush() {
            flushed = true
        }

        override fun close() = Unit
    }

    private class RejectingOffsetBlockDevice(
        override val blockSize: Int = 512,
        override val sizeBytes: Long,
    ) : SeekableBlockDevice {
        var flushed = false
            private set
        private var position = 0L

        override fun seek(byteOffset: Long) {
            position = byteOffset
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throw IOException("RejectingOffsetBlockDevice does not support reads")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset == 0) { "Non-zero offset writes are not implemented yet." }
            position += length
        }

        override fun flush() {
            flushed = true
        }

        override fun close() = Unit
    }
}
