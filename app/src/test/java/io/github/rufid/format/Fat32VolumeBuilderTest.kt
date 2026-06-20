package io.github.rufid.format

import io.github.rufid.partition.BootPayloadKind
import io.github.rufid.partition.FileSystemType
import io.github.rufid.partition.PartitionPlan
import io.github.rufid.partition.PartitionTableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fat32VolumeBuilderTest {
    @Test
    fun buildsFat32MetadataSectors() {
        val plan = PartitionPlan(
            tableType = PartitionTableType.Mbr,
            fileSystemType = FileSystemType.Fat32,
            bootPayloadKind = BootPayloadKind.None,
            startSector = 2048,
            sectorCount = 262_144,
            sectorSize = 512,
        )

        val builder = Fat32VolumeBuilder(plan, label = "RUFID")
        val layout = builder.layout()
        val boot = builder.bootSector(layout)
        val fsInfo = builder.fsInfoSector(layout)
        val fat = builder.firstFatSector(layout)

        assertTrue(layout.clusterCount >= 65_525)
        assertEquals(0x55.toByte(), boot[510])
        assertEquals(0xAA.toByte(), boot[511])
        assertEquals("FAT32   ", boot.decodeAscii(82, 8))
        assertEquals(0x52.toByte(), fsInfo[0])
        assertEquals(0x0ffffff8, fat.readLeInt(0))
        assertEquals(0x0fffffff, fat.readLeInt(8))
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).decodeToString()

    private fun ByteArray.readLeInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
}
