package io.github.rufid.format

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.partition.FileSystemType
import io.github.rufid.partition.PartitionPlan
import kotlin.math.ceil

data class Fat32Layout(
    val sectorSize: Int,
    val totalSectors: Long,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val fatCount: Int,
    val sectorsPerFat: Long,
    val rootCluster: Int,
    val dataSectors: Long,
    val clusterCount: Long,
) {
    val clusterSizeBytes: Long
        get() = sectorSize.toLong() * sectorsPerCluster.toLong()
}

class Fat32VolumeBuilder(
    private val partitionPlan: PartitionPlan,
    private val label: String = RecoveryVolumeLabel.DEFAULT_LABEL,
) {
    fun layout(): Fat32Layout {
        require(partitionPlan.fileSystemType == FileSystemType.Fat32)
        require(partitionPlan.sectorSize >= 512)
        require(partitionPlan.sectorCount <= 0xffff_ffffL)

        val reservedSectors = 32
        val fatCount = 2
        val sectorsPerCluster = chooseSectorsPerCluster(partitionPlan.sectorCount)
        val sectorsPerFat = calculateSectorsPerFat(
            totalSectors = partitionPlan.sectorCount,
            sectorSize = partitionPlan.sectorSize,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectors = reservedSectors,
            fatCount = fatCount,
        )
        val dataSectors = partitionPlan.sectorCount - reservedSectors - (fatCount * sectorsPerFat)
        val clusterCount = dataSectors / sectorsPerCluster
        require(clusterCount >= FAT32_MIN_CLUSTERS) {
            "Volume is too small for FAT32: clusters=$clusterCount"
        }
        require(clusterCount < FAT32_MAX_CLUSTERS) {
            "Volume is too large for FAT32: clusters=$clusterCount"
        }

        return Fat32Layout(
            sectorSize = partitionPlan.sectorSize,
            totalSectors = partitionPlan.sectorCount,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectors = reservedSectors,
            fatCount = fatCount,
            sectorsPerFat = sectorsPerFat,
            rootCluster = ROOT_CLUSTER,
            dataSectors = dataSectors,
            clusterCount = clusterCount,
        )
    }

    fun bootSector(layout: Fat32Layout = layout()): ByteArray =
        ByteArray(layout.sectorSize).also { bytes ->
            bytes[0] = 0xEB.toByte()
            bytes[1] = 0x58
            bytes[2] = 0x90.toByte()
            putAscii(bytes, 3, "RUFID   ", 8)
            putLeShort(bytes, 11, layout.sectorSize)
            bytes[13] = layout.sectorsPerCluster.toByte()
            putLeShort(bytes, 14, layout.reservedSectors)
            bytes[16] = layout.fatCount.toByte()
            putLeShort(bytes, 17, 0)
            putLeShort(bytes, 19, 0)
            bytes[21] = 0xF8.toByte()
            putLeShort(bytes, 22, 0)
            putLeShort(bytes, 24, 63)
            putLeShort(bytes, 26, 255)
            putLeInt(bytes, 28, partitionPlan.startSector.toInt())
            putLeInt(bytes, 32, layout.totalSectors.toInt())
            putLeInt(bytes, 36, layout.sectorsPerFat.toInt())
            putLeShort(bytes, 40, 0)
            putLeShort(bytes, 42, 0)
            putLeInt(bytes, 44, layout.rootCluster)
            putLeShort(bytes, 48, FSINFO_SECTOR)
            putLeShort(bytes, 50, BACKUP_BOOT_SECTOR)
            bytes[64] = 0x80.toByte()
            bytes[66] = 0x29
            putLeInt(bytes, 67, volumeId(layout))
            putAscii(bytes, 71, normalizedLabel(), 11)
            putAscii(bytes, 82, "FAT32   ", 8)
            bytes[510] = 0x55
            bytes[511] = 0xAA.toByte()
        }

    fun fsInfoSector(layout: Fat32Layout = layout()): ByteArray =
        ByteArray(layout.sectorSize).also { bytes ->
            putLeInt(bytes, 0, 0x41615252)
            putLeInt(bytes, 484, 0x61417272)
            putLeInt(bytes, 488, (layout.clusterCount - 1L).coerceAtMost(0xffff_ffffL).toInt())
            putLeInt(bytes, 492, 3)
            putLeInt(bytes, 508, 0xAA550000.toInt())
        }

    fun firstFatSector(layout: Fat32Layout = layout()): ByteArray =
        ByteArray(layout.sectorSize).also { bytes ->
            putLeInt(bytes, 0, 0x0ffffff8)
            putLeInt(bytes, 4, 0xffffffff.toInt())
            putLeInt(bytes, 8, 0x0fffffff)
        }

    private fun chooseSectorsPerCluster(totalSectors: Long): Int {
        val candidates = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)
        return candidates.firstOrNull { sectorsPerCluster ->
            val sectorsPerFat = calculateSectorsPerFat(
                totalSectors = totalSectors,
                sectorSize = partitionPlan.sectorSize,
                sectorsPerCluster = sectorsPerCluster,
                reservedSectors = 32,
                fatCount = 2,
            )
            val dataSectors = totalSectors - 32L - (2L * sectorsPerFat)
            val clusters = dataSectors / sectorsPerCluster
            clusters in FAT32_MIN_CLUSTERS until FAT32_MAX_CLUSTERS
        } ?: error("Unable to choose a FAT32 cluster size for $totalSectors sectors")
    }

    private fun calculateSectorsPerFat(
        totalSectors: Long,
        sectorSize: Int,
        sectorsPerCluster: Int,
        reservedSectors: Int,
        fatCount: Int,
    ): Long {
        var sectorsPerFat = 1L
        while (true) {
            val dataSectors = totalSectors - reservedSectors - (fatCount * sectorsPerFat)
            val clusters = dataSectors / sectorsPerCluster
            val required = ceil(((clusters + 2L) * 4L).toDouble() / sectorSize.toDouble()).toLong()
            if (required == sectorsPerFat) return required
            sectorsPerFat = required
        }
    }

    private fun normalizedLabel(): String =
        label.uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '_' || it == '-' }
            .take(11)
            .padEnd(11, ' ')

    private fun volumeId(layout: Fat32Layout): Int =
        0x52460000 or
            ((layout.sectorsPerCluster and 0xff) shl 8) or
            (layout.fatCount and 0xff)

    private fun putAscii(bytes: ByteArray, offset: Int, value: String, length: Int) {
        val padded = value.padEnd(length, ' ')
        for (index in 0 until length) bytes[offset + index] = padded[index].code.toByte()
    }

    private fun putLeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private companion object {
        const val FAT32_MIN_CLUSTERS = 65_525L
        const val FAT32_MAX_CLUSTERS = 0x0ffffff5L
        const val ROOT_CLUSTER = 2
        const val FSINFO_SECTOR = 1
        const val BACKUP_BOOT_SECTOR = 6
    }
}

class Fat32Formatter(
    private val blockDevice: SeekableBlockDevice,
) {
    fun format(
        partitionPlan: PartitionPlan,
        label: String = RecoveryVolumeLabel.DEFAULT_LABEL,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) {
        require(partitionPlan.fileSystemType == FileSystemType.Fat32)
        require(partitionPlan.sectorSize == blockDevice.blockSize) {
            "FAT32 formatter currently requires sector size to match device block size."
        }

        val builder = Fat32VolumeBuilder(partitionPlan, label)
        val layout = builder.layout()
        val boot = builder.bootSector(layout)
        val fsInfo = builder.fsInfoSector(layout)
        val firstFat = builder.firstFatSector(layout)
        val zero = ByteArray(layout.sectorSize)
        val totalWrites = layout.reservedSectors.toLong() +
            (layout.fatCount.toLong() * layout.sectorsPerFat) +
            layout.sectorsPerCluster.toLong()
        var writes = 0L

        fun writeRelativeSector(relativeSector: Long, bytes: ByteArray) {
            cancellationToken.throwIfCancelled()
            blockDevice.seek((partitionPlan.startSector + relativeSector) * layout.sectorSize.toLong())
            blockDevice.write(bytes, 0, bytes.size)
            writes++
            onProgress(Progress(writes, totalWrites, Progress.Phase.Writing))
        }

        for (sector in 0 until layout.reservedSectors) {
            val bytes = when (sector) {
                0, 6 -> boot
                1, 7 -> fsInfo
                else -> zero
            }
            writeRelativeSector(sector.toLong(), bytes)
        }

        for (fatIndex in 0 until layout.fatCount) {
            val fatStart = layout.reservedSectors.toLong() + (fatIndex.toLong() * layout.sectorsPerFat)
            writeRelativeSector(fatStart, firstFat)
            for (sector in 1L until layout.sectorsPerFat) writeRelativeSector(fatStart + sector, zero)
        }

        val rootStart = layout.reservedSectors.toLong() + (layout.fatCount.toLong() * layout.sectorsPerFat)
        for (sector in 0 until layout.sectorsPerCluster) writeRelativeSector(rootStart + sector, zero)
        blockDevice.flush()
        onProgress(Progress(totalWrites, totalWrites, Progress.Phase.Finished))
    }
}
