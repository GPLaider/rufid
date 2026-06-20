package io.github.rufid.format

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.partition.BootPayloadKind
import io.github.rufid.partition.FileSystemType
import io.github.rufid.partition.MbrTable
import io.github.rufid.partition.PartitionPlan
import io.github.rufid.partition.PartitionTableType
import java.io.IOException

data class UsbRecoveryPlan(
    val partitionPlan: PartitionPlan,
    val deviceSizeBytes: Long,
    val blockSize: Int,
    val firstWipeSectors: Long,
    val lastWipeSectors: Long,
) {
    val partitionSizeBytes: Long
        get() = partitionPlan.sectorCount * blockSize.toLong()

    val startOffsetBytes: Long
        get() = partitionPlan.startSector * blockSize.toLong()
}

object UsbRecoveryPlanner {
    const val DEFAULT_START_SECTOR = 2048L
    const val QUICK_WIPE_WINDOW_SECTORS = 2048L
    private const val MAX_MBR_SECTORS = 0xffff_ffffL

    fun create(
        deviceSizeBytes: Long,
        blockSize: Int,
        fileSystemType: FileSystemType = FileSystemType.Fat32,
    ): UsbRecoveryPlan {
        require(deviceSizeBytes > 0) { "Target device has unknown size." }
        require(blockSize >= 512) { "Unsupported USB block size: $blockSize" }
        require(deviceSizeBytes % blockSize.toLong() == 0L) {
            "USB capacity is not block-aligned: size=$deviceSizeBytes blockSize=$blockSize"
        }
        require(fileSystemType == FileSystemType.Fat32 || fileSystemType == FileSystemType.ExFat) {
            "USB recovery supports FAT32 and exFAT."
        }

        val totalSectors = deviceSizeBytes / blockSize.toLong()
        require(totalSectors > DEFAULT_START_SECTOR) {
            "USB device is too small for ${fileSystemType.displayName()} recovery."
        }
        val sectorCount = totalSectors - DEFAULT_START_SECTOR
        require(sectorCount <= MAX_MBR_SECTORS) {
            "MBR ${fileSystemType.displayName()} recovery currently supports up to 2 TiB targets."
        }

        val partitionPlan = PartitionPlan(
            tableType = PartitionTableType.Mbr,
            fileSystemType = fileSystemType,
            bootPayloadKind = BootPayloadKind.None,
            startSector = DEFAULT_START_SECTOR,
            sectorCount = sectorCount,
            sectorSize = blockSize,
        )
        partitionPlan.validate(deviceSizeBytes)
        when (fileSystemType) {
            FileSystemType.Fat32 -> Fat32VolumeBuilder(partitionPlan).layout()
            FileSystemType.ExFat -> ExFatVolumeBuilder(partitionPlan).layout()
            else -> error("Unsupported recovery filesystem: $fileSystemType")
        }

        return UsbRecoveryPlan(
            partitionPlan = partitionPlan,
            deviceSizeBytes = deviceSizeBytes,
            blockSize = blockSize,
            firstWipeSectors = minOf(QUICK_WIPE_WINDOW_SECTORS, totalSectors),
            lastWipeSectors = minOf(QUICK_WIPE_WINDOW_SECTORS, totalSectors),
        )
    }
}

class UsbRecoveryFormatter(
    private val blockDevice: SeekableBlockDevice,
) {
    fun reinitializeFat32(
        plan: UsbRecoveryPlan,
        label: String = RecoveryVolumeLabel.DEFAULT_LABEL,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) = reinitialize(plan, label, cancellationToken, onProgress)

    fun reinitialize(
        plan: UsbRecoveryPlan,
        label: String = RecoveryVolumeLabel.DEFAULT_LABEL,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) {
        require(plan.blockSize == blockDevice.blockSize) {
            "Recovery plan block size does not match USB device."
        }
        require(plan.deviceSizeBytes == blockDevice.sizeBytes) {
            "Recovery plan capacity does not match USB device."
        }

        val mbr = ByteArray(blockDevice.blockSize).also { sector ->
            MbrTable(plan.partitionPlan).toBytes().copyInto(sector)
        }
        val formatWrites = formatWriteCount(plan.partitionPlan, label)
        val wipeWrites = quickWipeRanges(plan).sumOf { it.count }
        val totalSteps = wipeWrites + 1L + formatWrites + verificationCount(plan.partitionPlan)
        var completed = 0L

        fun report(stepsDone: Long = completed, phase: Progress.Phase = Progress.Phase.Writing) {
            onProgress(Progress(stepsDone, totalSteps, phase))
        }

        for (range in quickWipeRanges(plan)) {
            writeZeroSectors(
                startSector = range.startSector,
                sectorCount = range.count,
                cancellationToken = cancellationToken,
            ) {
                completed++
                report()
            }
        }

        cancellationToken.throwIfCancelled()
        blockDevice.seek(0)
        blockDevice.write(mbr, 0, mbr.size)
        completed++
        report()

        val beforeFormat = completed
        when (plan.partitionPlan.fileSystemType) {
            FileSystemType.Fat32 -> Fat32Formatter(blockDevice).format(plan.partitionPlan, label, cancellationToken) { progress ->
                completed = beforeFormat + progress.bytesDone
                report()
            }
            FileSystemType.ExFat -> ExFatFormatter(blockDevice).format(plan.partitionPlan, label, cancellationToken) { progress ->
                completed = beforeFormat + progress.bytesDone
                report()
            }
            else -> error("Unsupported recovery filesystem: ${plan.partitionPlan.fileSystemType}")
        }
        completed = beforeFormat + formatWrites

        verifySector(0, mbr, cancellationToken)
        completed++
        report(phase = Progress.Phase.Verifying)

        verifyFileSystemMetadata(plan, label, cancellationToken) {
            completed++
            report(phase = Progress.Phase.Verifying)
        }

        blockDevice.flush()
        onProgress(Progress(totalSteps, totalSteps, Progress.Phase.Finished))
    }

    private fun formatWriteCount(partitionPlan: PartitionPlan, label: String): Long =
        when (partitionPlan.fileSystemType) {
            FileSystemType.Fat32 -> {
                val layout = Fat32VolumeBuilder(partitionPlan, label).layout()
                layout.reservedSectors.toLong() +
                    (layout.fatCount.toLong() * layout.sectorsPerFat) +
                    layout.sectorsPerCluster.toLong()
            }
            FileSystemType.ExFat -> {
                val layout = ExFatVolumeBuilder(partitionPlan, label).layout()
                24L +
                    layout.fatLength +
                    layout.bitmapClusterCount.toLong() * layout.sectorsPerCluster.toLong() +
                    layout.upcaseClusterCount.toLong() * layout.sectorsPerCluster.toLong() +
                    layout.rootDirectoryClusterCount.toLong() * layout.sectorsPerCluster.toLong()
            }
            else -> error("Unsupported recovery filesystem: ${partitionPlan.fileSystemType}")
        }

    private fun verificationCount(partitionPlan: PartitionPlan): Long =
        when (partitionPlan.fileSystemType) {
            FileSystemType.Fat32 -> 3L
            FileSystemType.ExFat -> 4L
            else -> error("Unsupported recovery filesystem: ${partitionPlan.fileSystemType}")
        }

    private fun verifyFileSystemMetadata(
        plan: UsbRecoveryPlan,
        label: String,
        cancellationToken: CancellationToken,
        onVerified: () -> Unit,
    ) {
        when (plan.partitionPlan.fileSystemType) {
            FileSystemType.Fat32 -> {
                val builder = Fat32VolumeBuilder(plan.partitionPlan, label)
                val layout = builder.layout()
                verifySector(plan.partitionPlan.startSector, builder.bootSector(layout), cancellationToken)
                onVerified()
                verifySector(plan.partitionPlan.startSector + 1L, builder.fsInfoSector(layout), cancellationToken)
                onVerified()
            }
            FileSystemType.ExFat -> {
                val builder = ExFatVolumeBuilder(plan.partitionPlan, label)
                val layout = builder.layout()
                verifySector(plan.partitionPlan.startSector, builder.mainBootSector(layout), cancellationToken)
                onVerified()
                verifySector(plan.partitionPlan.startSector + 11L, builder.bootChecksumSector(layout), cancellationToken)
                onVerified()
                verifySector(plan.partitionPlan.startSector + 12L, builder.mainBootSector(layout), cancellationToken)
                onVerified()
            }
            else -> error("Unsupported recovery filesystem: ${plan.partitionPlan.fileSystemType}")
        }
    }

    private fun quickWipeRanges(plan: UsbRecoveryPlan): List<SectorRange> {
        val totalSectors = plan.deviceSizeBytes / plan.blockSize.toLong()
        val first = SectorRange(0, plan.firstWipeSectors)
        val lastStart = (totalSectors - plan.lastWipeSectors).coerceAtLeast(0L)
        val last = SectorRange(lastStart, totalSectors - lastStart)
        return listOf(first, last)
            .filter { it.count > 0 }
            .sortedBy { it.startSector }
            .fold(mutableListOf()) { merged, range ->
                val previous = merged.lastOrNull()
                if (previous == null || previous.endSector < range.startSector) {
                    merged += range
                } else {
                    merged[merged.lastIndex] = SectorRange(
                        startSector = previous.startSector,
                        count = maxOf(previous.endSector, range.endSector) - previous.startSector,
                    )
                }
                merged
            }
    }

    private fun writeZeroSectors(
        startSector: Long,
        sectorCount: Long,
        cancellationToken: CancellationToken,
        onSectorWritten: () -> Unit,
    ): Long {
        val maxBufferBytes = 1024 * 1024
        val sectorsPerChunk = maxOf(1, maxBufferBytes / blockDevice.blockSize)
        val zero = ByteArray(sectorsPerChunk * blockDevice.blockSize)
        var written = 0L

        blockDevice.seek(startSector * blockDevice.blockSize.toLong())
        while (written < sectorCount) {
            cancellationToken.throwIfCancelled()
            val sectors = minOf(sectorsPerChunk.toLong(), sectorCount - written).toInt()
            val bytes = sectors * blockDevice.blockSize
            blockDevice.write(zero, 0, bytes)
            written += sectors.toLong()
            repeat(sectors) { onSectorWritten() }
        }
        return written
    }

    private fun verifySector(
        sector: Long,
        expected: ByteArray,
        cancellationToken: CancellationToken,
    ) {
        val actual = ByteArray(blockDevice.blockSize)
        cancellationToken.throwIfCancelled()
        blockDevice.seek(sector * blockDevice.blockSize.toLong())
        blockDevice.read(actual, 0, actual.size)
        if (!actual.contentEquals(expected)) {
            throw IOException("USB recovery verification failed at sector $sector.")
        }
    }

    private data class SectorRange(
        val startSector: Long,
        val count: Long,
    ) {
        val endSector: Long
            get() = startSector + count
    }
}

fun FileSystemType.displayName(): String =
    when (this) {
        FileSystemType.Fat16 -> "FAT16"
        FileSystemType.Fat32 -> "FAT32"
        FileSystemType.ExFat -> "exFAT"
        FileSystemType.Ntfs -> "NTFS"
        FileSystemType.Ext2 -> "ext2"
        FileSystemType.Ext3 -> "ext3"
        FileSystemType.Ext4 -> "ext4"
    }
