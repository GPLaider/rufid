package io.github.rufid.format

import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.partition.FileSystemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRecoveryFormatterTest {
    @Test
    fun reinitializesUsbAsSingleFat32MbrPartition() {
        val device = MemoryBlockDevice(sizeBytes = 96L * 1024L * 1024L)
        device.bytes.fill(0x5A.toByte())
        val plan = UsbRecoveryPlanner.create(device.sizeBytes, device.blockSize)
        val progress = mutableListOf<Int>()

        UsbRecoveryFormatter(device).reinitializeFat32(plan) { event ->
            progress += event.percent
        }

        assertEquals(0x55.toByte(), device.bytes[510])
        assertEquals(0xAA.toByte(), device.bytes[511])
        assertEquals(0x80.toByte(), device.bytes[446])
        assertEquals(0x0C.toByte(), device.bytes[450])
        assertEquals(UsbRecoveryPlanner.DEFAULT_START_SECTOR, device.bytes.readLeUInt(454))
        assertEquals(plan.partitionPlan.sectorCount, device.bytes.readLeUInt(458))

        val bootOffset = (plan.partitionPlan.startSector * device.blockSize).toInt()
        assertEquals(0x55.toByte(), device.bytes[bootOffset + 510])
        assertEquals(0xAA.toByte(), device.bytes[bootOffset + 511])
        assertEquals("FAT32   ", device.bytes.decodeAscii(bootOffset + 82, 8))
        assertEquals("USB DRIVE  ", device.bytes.decodeAscii(bootOffset + 71, 11))

        val lastSector = device.bytes.copyOfRange(device.bytes.size - device.blockSize, device.bytes.size)
        assertTrue(lastSector.all { it == 0.toByte() })
        assertTrue(device.flushed)
        assertEquals(100, progress.last())
    }

    @Test
    fun reinitializesUsbAsSingleExFatMbrPartition() {
        val device = MemoryBlockDevice(sizeBytes = 128L * 1024L * 1024L)
        device.bytes.fill(0x5A.toByte())
        val plan = UsbRecoveryPlanner.create(device.sizeBytes, device.blockSize, FileSystemType.ExFat)
        val layout = ExFatVolumeBuilder(plan.partitionPlan).layout()
        val progress = mutableListOf<Int>()

        UsbRecoveryFormatter(device).reinitialize(plan) { event ->
            progress += event.percent
        }

        assertEquals(0x55.toByte(), device.bytes[510])
        assertEquals(0xAA.toByte(), device.bytes[511])
        assertEquals(0x80.toByte(), device.bytes[446])
        assertEquals(0x07.toByte(), device.bytes[450])
        assertEquals(UsbRecoveryPlanner.DEFAULT_START_SECTOR, device.bytes.readLeUInt(454))
        assertEquals(plan.partitionPlan.sectorCount, device.bytes.readLeUInt(458))

        val bootOffset = (plan.partitionPlan.startSector * device.blockSize).toInt()
        assertEquals(0xEB.toByte(), device.bytes[bootOffset])
        assertEquals(0x76.toByte(), device.bytes[bootOffset + 1])
        assertEquals(0x90.toByte(), device.bytes[bootOffset + 2])
        assertEquals("EXFAT   ", device.bytes.decodeAscii(bootOffset + 3, 8))
        assertEquals(0x55.toByte(), device.bytes[bootOffset + 510])
        assertEquals(0xAA.toByte(), device.bytes[bootOffset + 511])

        val backupBootOffset = ((plan.partitionPlan.startSector + 12L) * device.blockSize).toInt()
        assertEquals("EXFAT   ", device.bytes.decodeAscii(backupBootOffset + 3, 8))

        val fatOffset = ((plan.partitionPlan.startSector + layout.fatOffset) * device.blockSize).toInt()
        assertEquals(0xfffffff8L, device.bytes.readLeUInt(fatOffset))
        assertEquals(0xffffffffL, device.bytes.readLeUInt(fatOffset + 4))

        val rootOffset = (ExFatFormatter.clusterAbsoluteSector(
            plan.partitionPlan,
            layout,
            layout.rootDirectoryFirstCluster,
        ) * device.blockSize).toInt()
        assertEquals(0x81.toByte(), device.bytes[rootOffset])
        assertEquals(0x82.toByte(), device.bytes[rootOffset + 32])
        assertEquals(0x83.toByte(), device.bytes[rootOffset + 64])
        assertEquals("USB DRIVE", String(device.bytes.copyOfRange(rootOffset + 66, rootOffset + 66 + 18), Charsets.UTF_16LE))

        val lastSector = device.bytes.copyOfRange(device.bytes.size - device.blockSize, device.bytes.size)
        assertTrue(lastSector.all { it == 0.toByte() })
        assertTrue(device.flushed)
        assertEquals(100, progress.last())
    }

    @Test
    fun rejectsUnknownCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbRecoveryPlanner.create(deviceSizeBytes = 0, blockSize = 512)
        }
    }

    private class MemoryBlockDevice(
        override val blockSize: Int = 512,
        override val sizeBytes: Long,
    ) : SeekableBlockDevice {
        val bytes = ByteArray(sizeBytes.toInt())
        var flushed = false
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

        override fun flush() {
            flushed = true
        }

        override fun close() = Unit
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).decodeToString()

    private fun ByteArray.readLeUInt(offset: Int): Long =
        (this[offset].toInt() and 0xff).toLong() or
            ((this[offset + 1].toInt() and 0xff).toLong() shl 8) or
            ((this[offset + 2].toInt() and 0xff).toLong() shl 16) or
            ((this[offset + 3].toInt() and 0xff).toLong() shl 24)
}
