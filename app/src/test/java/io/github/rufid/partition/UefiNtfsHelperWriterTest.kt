package io.github.rufid.partition

import io.github.rufid.core.BootMediaInspector
import io.github.rufid.core.CancellationToken
import io.github.rufid.core.SeekableBlockDevice
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UefiNtfsHelperWriterTest {
    private val testGuids = UefiGptCodec.testGuidSet()

    @Test
    fun mbrModeWritesHelperAndPartitionEntriesWithoutGpt() {
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        val helper = fatImage(1024 * 1024)

        val layout = UefiNtfsHelperWriter(target).write(
            helperImage = helper,
            mode = UefiNtfsPartitionTableMode.Mbr,
        )

        assertEquals(UefiNtfsPartitionTableMode.Mbr, layout.mode)
        assertEquals(0x07, target.bytes[446 + 4].toInt() and 0xff)
        assertEquals(0xEF, target.bytes[462 + 4].toInt() and 0xff)
        val helperOffset = layout.helperStartSector * 512L
        assertArrayEquals(helper, target.bytes.copyOfRange(helperOffset.toInt(), helperOffset.toInt() + helper.size))
        val lba1 = target.bytes.copyOfRange(512, 1024)
        assertFalse(String(lba1, 0, 8, Charsets.US_ASCII) == "EFI PART")
        assertTrue(target.flushed)
    }

    @Test
    fun gptModeWritesProtectiveMbrAndExactlyTwoOccupiedEntries() {
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        val helper = fatImage(1024 * 1024)
        val layout = UefiNtfsHelperWriter(target).write(
            helperImage = helper,
            mode = UefiNtfsPartitionTableMode.Gpt,
            guids = testGuids,
        )

        assertEquals(UefiNtfsPartitionTableMode.Gpt, layout.mode)
        assertEquals(0xEE, target.bytes[446 + 4].toInt() and 0xff)
        // No legacy 0x07/0xEF hybrid slots in protective MBR.
        assertEquals(0, target.bytes[462 + 4].toInt() and 0xff)

        val primaryHeader = target.bytes.copyOfRange(512, 1024)
        assertTrue(UefiGptCodec.verifyHeaderCrc(primaryHeader))
        val entries = target.bytes.copyOfRange(1024, 1024 + UefiGptCodec.PARTITION_ARRAY_BYTES)
        assertTrue(UefiGptCodec.verifyPartitionArrayCrcMatchesHeader(primaryHeader, entries))
        assertEquals(2, UefiGptCodec.countOccupiedEntries(entries))

        // No NTFS VBR planted: inspector still must not treat GPT LBA 1 as VBR.
        // Basic Data start is empty zeros -> not NTFS; gpt count still 2.
        val inspection = BootMediaInspector(target).inspect()
        assertTrue(inspection.hasGptSignature)
        assertEquals(2, inspection.gptPartitionCount)
        assertEquals(layout.dataStartSector, inspection.bootSector.lba)
        assertFalse(inspection.looksLikeNtfsVolume)
    }

    @Test
    fun gptModeWritesValidBackupHeaderBeyondIntByteRange() {
        val sizeBytes = 12L * 1024L * 1024L * 1024L
        val image = File.createTempFile("rufid-gpt-large-", ".img")
        try {
            RandomAccessFile(image, "rw").use { file ->
                file.setLength(sizeBytes)
                val target = object : SeekableBlockDevice {
                    override val blockSize: Int = 512
                    override val sizeBytes: Long = sizeBytes

                    override fun seek(byteOffset: Long) = file.seek(byteOffset)

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        file.read(buffer, offset, length)

                    override fun write(buffer: ByteArray, offset: Int, length: Int) =
                        file.write(buffer, offset, length)

                    override fun flush() = file.fd.sync()

                    override fun close() = Unit
                }
                UefiNtfsHelperWriter(target).write(
                    helperImage = fatImage(1024 * 1024),
                    mode = UefiNtfsPartitionTableMode.Gpt,
                    guids = testGuids,
                )
                val backupHeader = ByteArray(512)
                file.seek(sizeBytes - 512L)
                file.readFully(backupHeader)
                assertTrue(UefiGptCodec.verifyHeaderCrc(backupHeader))
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun gptModeInspectorUsesBasicDataStartAsNtfsVbr() {
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        val helper = fatImage(1024 * 1024)
        val layout = UefiNtfsHelperWriter(target).write(
            helperImage = helper,
            mode = UefiNtfsPartitionTableMode.Gpt,
            guids = testGuids,
        )

        // Plant a valid-looking NTFS VBR at the Basic Data partition start (layout only; no full FS).
        val vbrOffset = (layout.dataStartSector * 512L).toInt()
        target.bytes[vbrOffset + 3] = 'N'.code.toByte()
        target.bytes[vbrOffset + 4] = 'T'.code.toByte()
        target.bytes[vbrOffset + 5] = 'F'.code.toByte()
        target.bytes[vbrOffset + 6] = 'S'.code.toByte()
        target.bytes[vbrOffset + 7] = ' '.code.toByte()
        target.bytes[vbrOffset + 8] = ' '.code.toByte()
        target.bytes[vbrOffset + 9] = ' '.code.toByte()
        target.bytes[vbrOffset + 10] = ' '.code.toByte()
        target.bytes[vbrOffset + 510] = 0x55
        target.bytes[vbrOffset + 511] = 0xaa.toByte()

        val inspection = BootMediaInspector(target).inspect()
        assertTrue(inspection.hasGptSignature)
        assertEquals(2, inspection.gptPartitionCount)
        assertEquals(layout.dataStartSector, inspection.bootSector.lba)
        assertEquals("NTFS", inspection.bootSector.fileSystem)
        assertTrue(inspection.looksLikeNtfsVolume)
        // Must not report LBA 1 (GPT header) as the boot sector.
        assertTrue(inspection.bootSector.lba != 1L)
    }

    @Test
    fun gptPrimaryHeaderCrcCorruptionFailsReadback() {
        val helper = fatImage(1024 * 1024)
        val target = MemoryBlockDevice(
            sizeBytes = 64L * 1024L * 1024L,
            corruptRegion = CorruptRegion.GptPrimaryHeaderCrc,
        )
        val error = assertThrows(IOException::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Gpt, guids = testGuids)
        }
        assertTrue(error.message.orEmpty().contains("primary GPT header CRC"))
    }

    @Test
    fun gptPrimaryArrayCrcCorruptionFailsReadback() {
        val helper = fatImage(1024 * 1024)
        val target = MemoryBlockDevice(
            sizeBytes = 64L * 1024L * 1024L,
            corruptRegion = CorruptRegion.GptPrimaryArrayByte,
        )
        val error = assertThrows(IOException::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Gpt, guids = testGuids)
        }
        assertTrue(error.message.orEmpty().contains("partition-array CRC"))
    }

    @Test
    fun gptBackupHeaderCrcCorruptionFailsReadback() {
        val helper = fatImage(1024 * 1024)
        val sizeBytes = 64L * 1024L * 1024L
        val backupHeaderOffset = ((sizeBytes / 512L) - 1L) * 512L
        val target = MemoryBlockDevice(
            sizeBytes = sizeBytes,
            corruptRegion = CorruptRegion.GptBackupHeaderCrc,
            corruptAtByteOffset = backupHeaderOffset,
        )
        val error = assertThrows(IOException::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Gpt, guids = testGuids)
        }
        assertTrue(error.message.orEmpty().contains("backup GPT header CRC"))
    }

    @Test
    fun gptBackupArrayCrcCorruptionFailsReadback() {
        val helper = fatImage(1024 * 1024)
        val sizeBytes = 64L * 1024L * 1024L
        // Backup entries LBA = totalSectors - 1 - 32 = totalSectors - 33
        val backupEntriesOffset = ((sizeBytes / 512L) - 1L - UefiGptCodec.PARTITION_ARRAY_SECTORS) * 512L
        val target = MemoryBlockDevice(
            sizeBytes = sizeBytes,
            corruptRegion = CorruptRegion.GptBackupArrayByte,
            corruptAtByteOffset = backupEntriesOffset,
        )
        val error = assertThrows(IOException::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Gpt, guids = testGuids)
        }
        assertTrue(error.message.orEmpty().contains("partition-array CRC"))
    }

    @Test
    fun protectiveGptShortReadFailsInspector() {
        val helper = fatImage(1024 * 1024)
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Gpt, guids = testGuids)
        target.shortReadOnLba = 1L
        assertThrows(IOException::class.java) {
            BootMediaInspector(target).inspect()
        }
    }

    @Test
    fun helperByteMismatchOnReadbackFails() {
        val helper = fatImage(1024 * 1024)
        val target = MemoryBlockDevice(
            sizeBytes = 64L * 1024L * 1024L,
            corruptRegion = CorruptRegion.HelperFirstByte,
        )
        assertThrows(IOException::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Mbr)
        }
    }

    @Test
    fun shortReadDuringVerifyFails() {
        val helper = fatImage(1024 * 1024)
        val target = MemoryBlockDevice(64L * 1024L * 1024L, shortReadAfterFlush = true)
        val error = assertThrows(IOException::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Mbr)
        }
        assertTrue(error.message.orEmpty().contains("Short read"))
    }

    @Test
    fun malformedHelperImagePerformsNoWrites() {
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        assertThrows(IllegalArgumentException::class.java) {
            UefiNtfsHelperWriter(target).write(ByteArray(1024 * 1024), UefiNtfsPartitionTableMode.Mbr)
        }
        assertEquals(0, target.writeCalls)
    }

    @Test
    fun cancellationNeverReportsCompletedLayout() {
        val helper = fatImage(1024 * 1024)
        val token = CancellationToken.active()
        token.cancel()
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        assertThrows(Exception::class.java) {
            UefiNtfsHelperWriter(target).write(helper, UefiNtfsPartitionTableMode.Mbr, token)
        }
        assertEquals(0, target.writeCalls)
        assertFalse(target.flushed)
    }

    @Test
    fun missingHelperPayloadPerformsNoDeviceIo() {
        val target = MemoryBlockDevice(64L * 1024L * 1024L)
        assertThrows(IllegalStateException::class.java) {
            UefiNtfsRuntimeWriter(target) { null }.write()
        }
        assertEquals(0, target.seekCalls)
        assertEquals(0, target.writeCalls)
        assertEquals(0, target.flushCalls)
    }

    @Test
    fun ntfsVolumeLabelIsNotFabricatedFromVbrOffset48() {
        val image = ByteArray(8 * 512)
        image[510] = 0x55
        image[511] = 0xaa.toByte()
        image[446 + 4] = 0x07
        putLe32(image, 446 + 8, 1)
        putLe32(image, 446 + 12, 4)
        val vbr = 512
        // OEM NTFS; put garbage at offset 48 that must NOT become a label.
        for (i in 0 until 11) image[vbr + 48 + i] = 'X'.code.toByte()
        image[vbr + 3] = 'N'.code.toByte()
        image[vbr + 4] = 'T'.code.toByte()
        image[vbr + 5] = 'F'.code.toByte()
        image[vbr + 6] = 'S'.code.toByte()
        image[vbr + 510] = 0x55
        image[vbr + 511] = 0xaa.toByte()

        val result = BootMediaInspector(image.asBlockDevice()).inspect()
        assertTrue(result.looksLikeNtfsVolume)
        assertEquals("NTFS", result.bootSector.fileSystem)
        assertNull(result.bootSector.volumeLabel)
    }

    @Test
    fun pinnedSignedPayloadMatchRequiresPinnedHashAndSupportedArchitecture() {
        val payload = fatImage(1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
        val verifier = UefiNtfsSecureBootVerifier(
            allowedPayloads = mapOf(hash to setOf(UefiArchitecture.X64, UefiArchitecture.Arm64)),
        )
        assertTrue(verifier.matchesPinnedSignedPayload(payload, UefiArchitecture.X64))
        assertFalse(verifier.matchesPinnedSignedPayload(payload, UefiArchitecture.Ia32))
    }

    private fun fatImage(size: Int): ByteArray = ByteArray(size).also {
        it[11] = 0x00
        it[12] = 0x02
        it[510] = 0x55
        it[511] = 0xAA.toByte()
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.asBlockDevice(): SeekableBlockDevice {
        val device = MemoryBlockDevice(this.size.toLong())
        this.copyInto(device.bytes)
        return device
    }

    private enum class CorruptRegion {
        None,
        GptPrimaryHeaderCrc,
        GptPrimaryArrayByte,
        GptBackupHeaderCrc,
        GptBackupArrayByte,
        HelperFirstByte,
    }

    private class MemoryBlockDevice(
        override val sizeBytes: Long,
        private val corruptRegion: CorruptRegion = CorruptRegion.None,
        private val shortReadAfterFlush: Boolean = false,
        /** Absolute byte offset of the write start that should be corrupted (for backup regions). */
        private val corruptAtByteOffset: Long? = null,
    ) : SeekableBlockDevice {
        override val blockSize: Int = 512
        val bytes = ByteArray(sizeBytes.toInt())
        var writeCalls = 0
        var seekCalls = 0
        var flushCalls = 0
        var flushed = false
        var shortReadOnLba: Long? = null
        private var position = 0
        private var afterFlush = false

        override fun seek(byteOffset: Long) {
            seekCalls++
            position = byteOffset.toInt()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val lba = position.toLong() / blockSize
            if (shortReadOnLba != null && lba == shortReadOnLba) return 0
            if (shortReadAfterFlush && afterFlush) return 0
            bytes.copyInto(buffer, offset, position, position + length)
            position += length
            return length
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeCalls++
            buffer.copyInto(bytes, position, offset, offset + length)
            when (corruptRegion) {
                CorruptRegion.GptPrimaryHeaderCrc -> {
                    // Primary GPT header at LBA 1: flip stored header CRC at offset 16.
                    if (position == 512) {
                        bytes[512 + 16] = (bytes[512 + 16].toInt() xor 0xFF).toByte()
                    }
                }
                CorruptRegion.GptPrimaryArrayByte -> {
                    // Primary entries start at LBA 2 (1024).
                    if (position == 1024) {
                        bytes[1024] = (bytes[1024].toInt() xor 0x01).toByte()
                    }
                }
                CorruptRegion.GptBackupHeaderCrc -> {
                    // Backup header: flip stored header CRC at header+16.
                    if (corruptAtByteOffset != null && position.toLong() == corruptAtByteOffset) {
                        val crcOff = position + 16
                        bytes[crcOff] = (bytes[crcOff].toInt() xor 0xFF).toByte()
                    }
                }
                CorruptRegion.GptBackupArrayByte -> {
                    // Backup entries: flip first byte of the array.
                    if (corruptAtByteOffset != null && position.toLong() == corruptAtByteOffset) {
                        bytes[position] = (bytes[position].toInt() xor 0x01).toByte()
                    }
                }
                CorruptRegion.HelperFirstByte -> {
                    if (position > 2048 * 512) {
                        bytes[position] = (bytes[position].toInt() xor 0x01).toByte()
                    }
                }
                CorruptRegion.None -> Unit
            }
            position += length
        }

        override fun flush() {
            flushCalls++
            flushed = true
            afterFlush = true
        }

        override fun close() = Unit
    }
}
