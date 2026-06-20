package io.github.rufid.partition

enum class PartitionTableType {
    Mbr,
    Gpt,
}

enum class FileSystemType {
    Fat16,
    Fat32,
    ExFat,
    Ntfs,
    Ext2,
    Ext3,
    Ext4,
}

enum class BootPayloadKind {
    None,
    FreeDos,
    MsDos,
    UefiNtfs,
}

data class PartitionPlan(
    val tableType: PartitionTableType,
    val fileSystemType: FileSystemType,
    val bootPayloadKind: BootPayloadKind,
    val startSector: Long,
    val sectorCount: Long,
    val sectorSize: Int = 512,
) {
    fun validate(deviceSizeBytes: Long) {
        require(sectorSize > 0)
        require(startSector > 0)
        require(sectorCount > 0)
        val endByte = (startSector + sectorCount) * sectorSize.toLong()
        require(endByte <= deviceSizeBytes) {
            "Partition exceeds device size: end=$endByte device=$deviceSizeBytes"
        }
    }
}

