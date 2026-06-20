package io.github.rufid.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

class GptTable(
    private val partitionPlan: PartitionPlan,
    private val partitionName: String = "USB DRIVE",
    private val diskGuid: UUID = UUID.nameUUIDFromBytes("rufid-disk".toByteArray()),
    private val partitionGuid: UUID = UUID.nameUUIDFromBytes("rufid-partition".toByteArray()),
) {
    fun primaryHeaderAndEntries(): Pair<ByteArray, ByteArray> {
        require(partitionPlan.tableType == PartitionTableType.Gpt)
        val entries = ByteArray(PARTITION_ENTRY_SIZE * PARTITION_ENTRY_COUNT)
        writeBasicDataEntry(entries)

        val entriesCrc = crc32(entries)
        val header = ByteArray(partitionPlan.sectorSize)
        val usableLastLba = partitionPlan.startSector + partitionPlan.sectorCount - 1
        val backupLba = usableLastLba + 33

        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("EFI PART".toByteArray(Charsets.US_ASCII))
            putInt(0x00010000)
            putInt(92)
            putInt(0)
            putInt(0)
            putLong(1)
            putLong(backupLba)
            putLong(34)
            putLong(usableLastLba)
            putGuid(diskGuid)
            putLong(2)
            putInt(PARTITION_ENTRY_COUNT)
            putInt(PARTITION_ENTRY_SIZE)
            putInt(entriesCrc)
        }

        val headerCrc = crc32(header.copyOfRange(0, 92))
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(16, headerCrc)
        return header to entries
    }

    private fun writeBasicDataEntry(entries: ByteArray) {
        ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN).apply {
            putGuid(BASIC_DATA_PARTITION_TYPE)
            putGuid(partitionGuid)
            putLong(partitionPlan.startSector)
            putLong(partitionPlan.startSector + partitionPlan.sectorCount - 1)
            putLong(0)
            val name = partitionName.take(PARTITION_NAME_MAX_CHARS).toByteArray(Charsets.UTF_16LE)
            put(name)
        }
    }

    private fun ByteBuffer.putGuid(uuid: UUID) {
        order(ByteOrder.LITTLE_ENDIAN)
        putInt((uuid.mostSignificantBits ushr 32).toInt())
        putShort((uuid.mostSignificantBits ushr 16).toShort())
        putShort(uuid.mostSignificantBits.toShort())
        order(ByteOrder.BIG_ENDIAN)
        putLong(uuid.leastSignificantBits)
        order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun crc32(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }

    companion object {
        private const val PARTITION_ENTRY_SIZE = 128
        private const val PARTITION_ENTRY_COUNT = 128
        private const val PARTITION_NAME_MAX_CHARS = 36
        private val BASIC_DATA_PARTITION_TYPE = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    }
}
