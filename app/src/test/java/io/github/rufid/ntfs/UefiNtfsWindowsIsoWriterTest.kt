package io.github.rufid.ntfs

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.ExtractableIsoFile
import io.github.rufid.core.OperationCancelledException
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.partition.UefiNtfsPartitionTableMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UefiNtfsWindowsIsoWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun usbOffsetCopyReadbackMismatchFails() {
        val device = MemoryDevice(64L * 1024L * 1024L)
        // Corrupt on compare by flipping a byte after write via custom device
        val corrupt = object : SeekableBlockDevice by device {
            var writes = 0
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes++
                device.write(buffer, offset, length)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = device.read(buffer, offset, length)
                // Flip first data byte when reading deep into data region after enough traffic
                if (device.position >= 2048L * 512L && n > 0) {
                    buffer[offset] = (buffer[offset].toInt() xor 0xFF).toByte()
                }
                return n
            }
        }
        // Direct range IO unit
        val file = temp.newFile("stage.img")
        RandomAccessFile(file, "rw").use { it.setLength(4096); it.write(ByteArray(4096) { 1 }) }
        BlockDeviceRangeIO.writeFileToRange(device, 2048L * 512L, file, 4096)
        assertThrows(IOException::class.java) {
            BlockDeviceRangeIO.compareFileToRange(corrupt, 2048L * 512L, file, 4096)
        }
    }

    @Test
    fun publishTablesLastAndNoSuccessOnPartialFailure() {
        val nativeDir = temp.newFolder("lib")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        val cache = temp.newFolder("cache")
        val helper = fatHelper(1024 * 1024)
        val device = MemoryDevice(128L * 1024L * 1024L)

        // Builder succeeds with a real sparse file content we control
        val builder = object : SparseNtfsImageBuilder(nativeDir) {
            override fun buildAndVerify(
                cacheDir: File,
                sizeBytes: Long,
                files: List<ExtractableIsoFile>,
                cancellationToken: CancellationToken,
                requiredSourceBytes: Long,
                onProgress: (Progress) -> Unit,
            ): BuiltImage {
                val image = File(cacheDir, "ok.img")
                RandomAccessFile(image, "rw").use { raf ->
                    raf.setLength(sizeBytes)
                    raf.write(ByteArray(512) { 0x11 })
                }
                return BuiltImage(image, sizeBytes, sizeBytes / 512)
            }
        }

        // Fail USB compare by using a device that corrupts data reads after first invalidate
        val flaky = CorruptingAfterDataWriteDevice(device)
        val writer = UefiNtfsWindowsIsoWriter(
            blockDevice = flaky,
            imageBuilder = builder,
            helperImage = helper,
            cacheDir = cache,
            mode = UefiNtfsPartitionTableMode.Mbr,
        )
        assertThrows(IOException::class.java) {
            writer.write(listOf(memoryFile("sources/hello.txt", "hi".toByteArray())))
        }
        // Partition table must remain invalid (no 55AA success publish)
        assertFalse(
            "partial failure must not leave valid MBR signature",
            device.bytes[510] == 0x55.toByte() && device.bytes[511] == 0xaa.toByte() &&
                device.bytes[446 + 4] == 0x07.toByte(),
        )
        // staging deleted
        assertTrue(cache.listFiles()?.none { it.extension == "img" } != false)
    }

    @Test
    fun postPublishInspectionFailureInvalidatesPartitionMetadata() {
        val nativeDir = temp.newFolder("libpost")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        val cache = temp.newFolder("cachepost")
        val helper = fatHelper(1024 * 1024)
        val device = MemoryDevice(128L * 1024L * 1024L)
        val builder = object : SparseNtfsImageBuilder(nativeDir) {
            override fun buildAndVerify(
                cacheDir: File,
                sizeBytes: Long,
                files: List<ExtractableIsoFile>,
                cancellationToken: CancellationToken,
                requiredSourceBytes: Long,
                onProgress: (Progress) -> Unit,
            ): BuiltImage {
                val image = File(cacheDir, "invalid-vbr.img")
                RandomAccessFile(image, "rw").use { raf ->
                    raf.setLength(sizeBytes)
                    val vbr = ByteArray(512)
                    "BROKEN  ".toByteArray().copyInto(vbr, destinationOffset = 3)
                    vbr[510] = 0x55
                    vbr[511] = 0xaa.toByte()
                    raf.write(vbr)
                }
                return BuiltImage(image, sizeBytes, sizeBytes / 512)
            }
        }
        val writer = UefiNtfsWindowsIsoWriter(
            blockDevice = device,
            imageBuilder = builder,
            helperImage = helper,
            cacheDir = cache,
            mode = UefiNtfsPartitionTableMode.Mbr,
        )

        assertThrows(IOException::class.java) {
            writer.write(listOf(memoryFile("sources/hello.txt", "hi".toByteArray())))
        }

        val primaryBytes = BlockDeviceRangeIO.GPT_PRIMARY_WIPE_SECTORS * device.blockSize
        assertTrue(device.bytes.copyOfRange(0, primaryBytes).all { it == 0.toByte() })
        val backupBytes = BlockDeviceRangeIO.GPT_BACKUP_WIPE_SECTORS * device.blockSize
        assertTrue(device.bytes.copyOfRange(device.bytes.size - backupBytes, device.bytes.size).all { it == 0.toByte() })
        assertTrue(cache.listFiles()?.none { it.extension == "img" } != false)
    }

    @Test
    fun preCancelledWriteDoesNotTouchExistingPartitionMetadata() {
        val nativeDir = nativeStubDir("libprecancel")
        val cache = temp.newFolder("cacheprecancel")
        val device = MemoryDevice(128L * 1024L * 1024L)
        val before = seedAndSnapshotPartitionMetadata(device)
        val token = CancellationToken.active().also { it.cancel() }
        val writer = UefiNtfsWindowsIsoWriter(
            blockDevice = device,
            imageBuilder = failingBuilder(nativeDir, AssertionError("build must not start")),
            helperImage = fatHelper(1024 * 1024),
            cacheDir = cache,
            mode = UefiNtfsPartitionTableMode.Mbr,
        )

        assertThrows(OperationCancelledException::class.java) {
            writer.write(
                files = listOf(memoryFile("sources/hello.txt", "hi".toByteArray())),
                cancellationToken = token,
            )
        }

        assertPartitionMetadataEquals(before, device)
    }

    @Test
    fun cacheBuildFailureDoesNotTouchExistingPartitionMetadata() {
        val nativeDir = nativeStubDir("libbuildfail")
        val cache = temp.newFolder("cachebuildfail")
        val device = MemoryDevice(128L * 1024L * 1024L)
        val before = seedAndSnapshotPartitionMetadata(device)
        val writer = UefiNtfsWindowsIsoWriter(
            blockDevice = device,
            imageBuilder = failingBuilder(nativeDir, IOException("cache build failed")),
            helperImage = fatHelper(1024 * 1024),
            cacheDir = cache,
            mode = UefiNtfsPartitionTableMode.Mbr,
        )

        assertThrows(IOException::class.java) {
            writer.write(listOf(memoryFile("sources/hello.txt", "hi".toByteArray())))
        }

        assertPartitionMetadataEquals(before, device)
    }

    @Test
    fun successfulTransactionPublishesMbrLast() {
        val nativeDir = temp.newFolder("libok")
        File(nativeDir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(nativeDir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
        val cache = temp.newFolder("cacheok")
        val helper = fatHelper(1024 * 1024)
        val device = MemoryDevice(128L * 1024L * 1024L)
        val phases = mutableListOf<Progress.Phase>()
        val builder = object : SparseNtfsImageBuilder(nativeDir) {
            override fun buildAndVerify(
                cacheDir: File,
                sizeBytes: Long,
                files: List<ExtractableIsoFile>,
                cancellationToken: CancellationToken,
                requiredSourceBytes: Long,
                onProgress: (Progress) -> Unit,
            ): BuiltImage {
                val image = File(cacheDir, "ok.img")
                RandomAccessFile(image, "rw").use { raf ->
                    raf.setLength(sizeBytes)
                    // minimal NTFS OEM in VBR position 0 of image
                    val vbr = ByteArray(512)
                    vbr[3] = 'N'.code.toByte()
                    vbr[4] = 'T'.code.toByte()
                    vbr[5] = 'F'.code.toByte()
                    vbr[6] = 'S'.code.toByte()
                    vbr[7] = ' '.code.toByte()
                    vbr[8] = ' '.code.toByte()
                    vbr[9] = ' '.code.toByte()
                    vbr[10] = ' '.code.toByte()
                    vbr[510] = 0x55
                    vbr[511] = 0xaa.toByte()
                    raf.write(vbr)
                }
                onProgress(Progress(0, 1, Progress.Phase.Populating))
                onProgress(Progress(1, 1, Progress.Phase.VerifyingNtfs))
                return BuiltImage(image, sizeBytes, sizeBytes / 512)
            }
        }
        val writer = UefiNtfsWindowsIsoWriter(
            blockDevice = device,
            imageBuilder = builder,
            helperImage = helper,
            cacheDir = cache,
            mode = UefiNtfsPartitionTableMode.Mbr,
        )
        val result = writer.write(
            files = listOf(memoryFile("sources/hello.txt", "hi".toByteArray())),
            onProgress = { phases += it.phase },
        )
        assertEquals(UefiNtfsPartitionTableMode.Mbr, result.layout.mode)
        assertEquals(0x07, device.bytes[446 + 4].toInt() and 0xff)
        assertEquals(0xEF, device.bytes[462 + 4].toInt() and 0xff)
        assertEquals(0x55, device.bytes[510].toInt() and 0xff)
        assertEquals(0xaa, device.bytes[511].toInt() and 0xff)
        // data VBR present + post-publish inspection path
        val dataOff = (result.layout.dataStartSector * 512L).toInt()
        assertEquals('N'.code, device.bytes[dataOff + 3].toInt() and 0xff)
        assertTrue(cache.listFiles()?.none { it.extension == "img" } != false)
        assertTrue(phases.contains(Progress.Phase.Populating))
        assertTrue(phases.contains(Progress.Phase.CopyingSparse))
        assertTrue(phases.contains(Progress.Phase.ComparingSparse))
        assertTrue(phases.contains(Progress.Phase.Finished))
    }

    @Test
    fun invalidateWipesPrimaryAndBackupGptRegions() {
        val device = MemoryDevice(64L * 1024L * 1024L)
        // Plant markers in primary and backup GPT regions.
        device.bytes[0] = 0x55
        device.bytes[511] = 0xaa.toByte()
        device.bytes[512 + 0] = 'E'.code.toByte()
        val last = device.bytes.size - 512
        device.bytes[last] = 'E'.code.toByte()
        device.bytes[last + 7] = 'T'.code.toByte()
        BlockDeviceRangeIO.invalidatePartitionMetadata(device)
        // First 34 sectors zeroed
        for (i in 0 until 34 * 512) {
            assertEquals(0, device.bytes[i].toInt())
        }
        // Last 33 sectors zeroed
        val totalSectors = device.sizeBytes / 512
        val backupStart = ((totalSectors - 33) * 512).toInt()
        for (i in backupStart until device.bytes.size) {
            assertEquals(0, device.bytes[i].toInt())
        }
    }

    @Test
    fun sparseExtentsFallbackIsFullRangeOnUnsupportedSeek() {
        val f = temp.newFile("sparse-fallback.img")
        RandomAccessFile(f, "rw").use { it.setLength(4096); it.write(ByteArray(100) { 1 }) }
        val extents = SparseFileExtents.listAllocatedExtents(f, 4096)
        assertEquals(1, extents.size)
        assertEquals(0L, extents[0].offset)
        assertEquals(4096L, extents[0].length)
    }

    @Test
    fun pathNotSanitizedBeforeValidation() {
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("sources\\evil.txt")
        }
        assertThrows(IOException::class.java) {
            NtfsStreamProtocol.requireSafeRelativePath("/sources/evil.txt")
        }
    }

    @Test
    fun backendModeKeepsWimUnsplitOnNtfs() {
        assertTrue(WindowsInstallBackendMode.NtfsUefiMbr.keepInstallWimUnsplit)
        assertTrue(WindowsInstallBackendMode.NtfsUefiGpt.keepInstallWimUnsplit)
        assertFalse(WindowsInstallBackendMode.Fat32Extraction.keepInstallWimUnsplit)
        assertEquals(
            UefiNtfsPartitionTableMode.Mbr,
            WindowsInstallBackendMode.NtfsUefiMbr.partitionTableMode,
        )
        assertEquals(
            UefiNtfsPartitionTableMode.Gpt,
            WindowsInstallBackendMode.NtfsUefiGpt.partitionTableMode,
        )
    }

    private fun fatHelper(size: Int): ByteArray = ByteArray(size).also {
        it[11] = 0x00
        it[12] = 0x02
        it[510] = 0x55
        it[511] = 0xAA.toByte()
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

    private fun nativeStubDir(name: String): File = temp.newFolder(name).also { dir ->
        File(dir, NtfsNativeTools.MKNTFS_SO).apply { writeText("x"); setExecutable(true) }
        File(dir, NtfsNativeTools.STREAM_SO).apply { writeText("x"); setExecutable(true) }
    }

    private fun failingBuilder(nativeDir: File, error: Throwable): SparseNtfsImageBuilder =
        object : SparseNtfsImageBuilder(nativeDir) {
            override fun buildAndVerify(
                cacheDir: File,
                sizeBytes: Long,
                files: List<ExtractableIsoFile>,
                cancellationToken: CancellationToken,
                requiredSourceBytes: Long,
                onProgress: (Progress) -> Unit,
            ): BuiltImage = throw error
        }

    private data class PartitionMetadataSnapshot(
        val primary: ByteArray,
        val backup: ByteArray,
    )

    private fun seedAndSnapshotPartitionMetadata(device: MemoryDevice): PartitionMetadataSnapshot {
        val primaryBytes = BlockDeviceRangeIO.GPT_PRIMARY_WIPE_SECTORS * device.blockSize
        device.bytes.fill(0x5a.toByte(), 0, primaryBytes)
        val backupBytes = BlockDeviceRangeIO.GPT_BACKUP_WIPE_SECTORS * device.blockSize
        val backupStart = device.bytes.size - backupBytes
        device.bytes.fill(0x6b.toByte(), backupStart, device.bytes.size)
        return PartitionMetadataSnapshot(
            primary = device.bytes.copyOfRange(0, primaryBytes),
            backup = device.bytes.copyOfRange(backupStart, device.bytes.size),
        )
    }

    private fun assertPartitionMetadataEquals(before: PartitionMetadataSnapshot, device: MemoryDevice) {
        assertArrayEquals(before.primary, device.bytes.copyOfRange(0, before.primary.size))
        assertArrayEquals(
            before.backup,
            device.bytes.copyOfRange(device.bytes.size - before.backup.size, device.bytes.size),
        )
    }

    private class MemoryDevice(override val sizeBytes: Long) : SeekableBlockDevice {
        override val blockSize: Int = 512
        val bytes = ByteArray(sizeBytes.toInt())
        var position = 0L
        override fun seek(byteOffset: Long) {
            position = byteOffset
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
            position += length
            return length
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            buffer.copyInto(bytes, position.toInt(), offset, offset + length)
            position += length
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    /** After data-region writes, flips bytes on read to force compare failure; keeps LBA0 invalid. */
    private class CorruptingAfterDataWriteDevice(
        private val inner: MemoryDevice,
    ) : SeekableBlockDevice {
        override val blockSize: Int = inner.blockSize
        override val sizeBytes: Long = inner.sizeBytes
        private var dataWritten = false
        override fun seek(byteOffset: Long) = inner.seek(byteOffset)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (inner.position >= 2048L * 512L) dataWritten = true
            inner.write(buffer, offset, length)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val pos = inner.position
            val n = inner.read(buffer, offset, length)
            if (dataWritten && pos >= 2048L * 512L && n > 0) {
                buffer[offset] = (buffer[offset].toInt() xor 1).toByte()
            }
            return n
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}
