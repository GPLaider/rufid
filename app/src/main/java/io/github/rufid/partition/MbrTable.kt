package io.github.rufid.partition

class MbrTable(
    private val partitionPlan: PartitionPlan,
) {
    fun toBytes(): ByteArray {
        require(partitionPlan.tableType == PartitionTableType.Mbr)
        require(partitionPlan.startSector <= 0xffff_ffffL)
        require(partitionPlan.sectorCount <= 0xffff_ffffL)

        val bytes = ByteArray(512)
        val offset = 446
        bytes[offset] = 0x80.toByte()
        bytes[offset + 4] = partitionType(partitionPlan.fileSystemType).toByte()
        putLittleEndianInt(bytes, offset + 8, partitionPlan.startSector.toInt())
        putLittleEndianInt(bytes, offset + 12, partitionPlan.sectorCount.toInt())
        bytes[510] = 0x55.toByte()
        bytes[511] = 0xAA.toByte()
        return bytes
    }

    private fun partitionType(fileSystemType: FileSystemType): Int =
        when (fileSystemType) {
            FileSystemType.Fat16 -> 0x0E
            FileSystemType.Fat32 -> 0x0C
            FileSystemType.ExFat -> 0x07
            FileSystemType.Ntfs -> 0x07
            FileSystemType.Ext2, FileSystemType.Ext3, FileSystemType.Ext4 -> 0x83
        }

    private fun putLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
