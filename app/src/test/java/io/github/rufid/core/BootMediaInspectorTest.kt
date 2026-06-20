package io.github.rufid.core

import io.github.rufid.format.UsbRecoveryFormatter
import io.github.rufid.format.UsbRecoveryPlanner
import io.github.rufid.partition.FileSystemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootMediaInspectorTest {
    @Test
    fun detectsFreeDosLiteUsbBootMedia() {
        val image = ByteArray(128 * 512)
        image[510] = 0x55
        image[511] = 0xaa.toByte()

        val entry = 446
        image[entry] = 0x80.toByte()
        image[entry + 4] = 0x04
        image.writeU32Le(entry + 8, 63)
        image.writeU32Le(entry + 12, 64)

        val vbr = 63 * 512
        image.writeAscii(vbr + 3, "FRDOS5.1")
        image.writeAscii(vbr + 43, "FD14-LITE  ")
        image.writeAscii(vbr + 54, "FAT16   ")
        image[vbr + 510] = 0x55
        image[vbr + 511] = 0xaa.toByte()

        val result = BootMediaInspector(FakeBlockDevice(image)).inspect()

        assertTrue(result.hasMbrSignature)
        assertEquals(1, result.partitions.size)
        assertEquals("0x04", result.partitions.single().typeHex)
        assertEquals(63L, result.bootSector.lba)
        assertEquals("FRDOS5.1", result.bootSector.oemName)
        assertEquals("FD14-LITE", result.bootSector.volumeLabel)
        assertEquals("FAT16", result.bootSector.fileSystem)
        assertTrue(result.looksLikeFreeDos)
    }

    @Test
    fun detectsExFatRecoveryMedia() {
        val device = WritableFakeBlockDevice(ByteArray(8 * 1024 * 1024))
        val plan = UsbRecoveryPlanner.create(device.sizeBytes, device.blockSize, FileSystemType.ExFat)
        UsbRecoveryFormatter(device).reinitialize(plan) { }

        val result = BootMediaInspector(device).inspect()

        assertTrue(result.hasMbrSignature)
        assertEquals("0x07", result.partitions.single().typeHex)
        assertEquals(UsbRecoveryPlanner.DEFAULT_START_SECTOR, result.bootSector.lba)
        assertEquals("EXFAT", result.bootSector.oemName)
        assertEquals("exFAT", result.bootSector.fileSystem)
        assertEquals("USB DRIVE", result.bootSector.volumeLabel)
    }

    private class FakeBlockDevice(
        private val bytes: ByteArray,
    ) : SeekableBlockDevice {
        override val blockSize: Int = 512
        override val sizeBytes: Long = bytes.size.toLong()
        private var position = 0

        override fun seek(byteOffset: Long) {
            position = byteOffset.toInt()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            bytes.copyInto(buffer, offset, position, position + length)
            position += length
            return length
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) = error("read-only fake")
        override fun flush() = Unit
        override fun close() = Unit
    }

    private class WritableFakeBlockDevice(
        private val bytes: ByteArray,
    ) : SeekableBlockDevice {
        override val blockSize: Int = 512
        override val sizeBytes: Long = bytes.size.toLong()
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
            buffer.copyInto(bytes, position, offset, offset + length)
            position += length
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun ByteArray.writeU32Le(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = ((value ushr 16) and 0xff).toByte()
        this[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.encodeToByteArray().copyInto(this, offset)
    }
}
