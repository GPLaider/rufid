package io.github.rufid.format

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.partition.FileSystemType
import io.github.rufid.partition.PartitionPlan
import kotlin.math.ceil

data class ExFatLayout(
    val sectorSize: Int,
    val volumeSectors: Long,
    val bytesPerSectorShift: Int,
    val sectorsPerClusterShift: Int,
    val sectorsPerCluster: Int,
    val fatOffset: Long,
    val fatLength: Long,
    val clusterHeapOffset: Long,
    val clusterCount: Long,
    val bitmapFirstCluster: Int,
    val bitmapClusterCount: Int,
    val bitmapLength: Long,
    val upcaseFirstCluster: Int,
    val upcaseClusterCount: Int,
    val upcaseLength: Long,
    val rootDirectoryFirstCluster: Int,
    val rootDirectoryClusterCount: Int,
) {
    val clusterSizeBytes: Long
        get() = sectorSize.toLong() * sectorsPerCluster.toLong()

    val allocatedClusterCount: Long
        get() = bitmapClusterCount.toLong() + upcaseClusterCount.toLong() + rootDirectoryClusterCount.toLong()
}

class ExFatVolumeBuilder(
    private val partitionPlan: PartitionPlan,
    private val label: String = RecoveryVolumeLabel.DEFAULT_LABEL,
) {
    fun layout(): ExFatLayout {
        require(partitionPlan.fileSystemType == FileSystemType.ExFat)
        require(partitionPlan.sectorSize in 512..4096)
        require(partitionPlan.sectorSize.isPowerOfTwo())

        val bytesPerSectorShift = partitionPlan.sectorSize.log2()
        val sectorsPerCluster = chooseSectorsPerCluster(partitionPlan.sectorCount, partitionPlan.sectorSize)
        val sectorsPerClusterShift = sectorsPerCluster.log2()

        var fatLength = 1L
        var clusterHeapOffset: Long
        var clusterCount: Long
        while (true) {
            clusterHeapOffset = alignUp(FAT_OFFSET + fatLength, sectorsPerCluster.toLong())
            clusterCount = (partitionPlan.sectorCount - clusterHeapOffset) / sectorsPerCluster.toLong()
            require(clusterCount > 16) { "Volume is too small for exFAT." }
            require(clusterCount <= EXFAT_RECOMMENDED_MAX_CLUSTERS) {
                "exFAT cluster count is too large for the selected recovery layout."
            }
            val requiredFat = ceil(((clusterCount + 2L) * 4L).toDouble() / partitionPlan.sectorSize.toDouble()).toLong()
            if (requiredFat == fatLength) break
            fatLength = requiredFat
        }

        val clusterSize = partitionPlan.sectorSize.toLong() * sectorsPerCluster.toLong()
        val bitmapLength = ceil(clusterCount.toDouble() / 8.0).toLong()
        val bitmapClusters = ceil(bitmapLength.toDouble() / clusterSize.toDouble()).toInt()
        val upcaseLength = UPCASE_TABLE_BYTES.toLong()
        val upcaseClusters = ceil(upcaseLength.toDouble() / clusterSize.toDouble()).toInt()
        val rootClusters = 1
        val allocatedClusters = bitmapClusters + upcaseClusters + rootClusters
        require(clusterCount > allocatedClusters) { "Volume is too small for exFAT metadata." }

        val bitmapFirst = FIRST_CLUSTER
        val upcaseFirst = bitmapFirst + bitmapClusters
        val rootFirst = upcaseFirst + upcaseClusters

        return ExFatLayout(
            sectorSize = partitionPlan.sectorSize,
            volumeSectors = partitionPlan.sectorCount,
            bytesPerSectorShift = bytesPerSectorShift,
            sectorsPerClusterShift = sectorsPerClusterShift,
            sectorsPerCluster = sectorsPerCluster,
            fatOffset = FAT_OFFSET,
            fatLength = fatLength,
            clusterHeapOffset = clusterHeapOffset,
            clusterCount = clusterCount,
            bitmapFirstCluster = bitmapFirst,
            bitmapClusterCount = bitmapClusters,
            bitmapLength = bitmapLength,
            upcaseFirstCluster = upcaseFirst,
            upcaseClusterCount = upcaseClusters,
            upcaseLength = upcaseLength,
            rootDirectoryFirstCluster = rootFirst,
            rootDirectoryClusterCount = rootClusters,
        )
    }

    fun mainBootSector(layout: ExFatLayout = layout()): ByteArray =
        ByteArray(layout.sectorSize).also { bytes ->
            bytes[0] = 0xEB.toByte()
            bytes[1] = 0x76
            bytes[2] = 0x90.toByte()
            putAscii(bytes, 3, "EXFAT   ")
            putLeLong(bytes, 64, partitionPlan.startSector)
            putLeLong(bytes, 72, layout.volumeSectors)
            putLeInt(bytes, 80, layout.fatOffset)
            putLeInt(bytes, 84, layout.fatLength)
            putLeInt(bytes, 88, layout.clusterHeapOffset)
            putLeInt(bytes, 92, layout.clusterCount)
            putLeInt(bytes, 96, layout.rootDirectoryFirstCluster.toLong())
            putLeInt(bytes, 100, volumeSerial(layout).toLong())
            putLeShort(bytes, 104, 0x0100)
            putLeShort(bytes, 106, 0)
            bytes[108] = layout.bytesPerSectorShift.toByte()
            bytes[109] = layout.sectorsPerClusterShift.toByte()
            bytes[110] = 1
            bytes[111] = 0x80.toByte()
            bytes[112] = ((layout.allocatedClusterCount * 100L) / layout.clusterCount).coerceIn(0, 100).toByte()
            bytes.fill(0xF4.toByte(), 120, 510)
            bytes[510] = 0x55
            bytes[511] = 0xAA.toByte()
        }

    fun extendedBootSector(layout: ExFatLayout = layout()): ByteArray =
        ByteArray(layout.sectorSize).also { bytes ->
            putLeInt(bytes, layout.sectorSize - 4, 0xAA550000L)
        }

    fun bootChecksumSector(layout: ExFatLayout = layout()): ByteArray {
        val region = bootRegionWithoutChecksum(layout)
        val checksum = bootChecksum(region)
        return ByteArray(layout.sectorSize).also { bytes ->
            for (offset in bytes.indices step 4) putLeInt(bytes, offset, checksum)
        }
    }

    fun fatSector(layout: ExFatLayout = layout()): ByteArray =
        ByteArray(layout.sectorSize).also { bytes ->
            putLeInt(bytes, 0, 0xfffffff8L)
            putLeInt(bytes, 4, 0xffffffffL)
            putClusterChain(bytes, layout.bitmapFirstCluster, layout.bitmapClusterCount)
            putClusterChain(bytes, layout.upcaseFirstCluster, layout.upcaseClusterCount)
            putClusterChain(bytes, layout.rootDirectoryFirstCluster, layout.rootDirectoryClusterCount)
        }

    fun allocationBitmap(layout: ExFatLayout = layout()): ByteArray {
        val bytes = ByteArray((layout.bitmapClusterCount * layout.clusterSizeBytes).toInt())
        for (cluster in layout.bitmapFirstCluster until layout.bitmapFirstCluster + layout.bitmapClusterCount) {
            markAllocated(bytes, cluster)
        }
        for (cluster in layout.upcaseFirstCluster until layout.upcaseFirstCluster + layout.upcaseClusterCount) {
            markAllocated(bytes, cluster)
        }
        for (cluster in layout.rootDirectoryFirstCluster until layout.rootDirectoryFirstCluster + layout.rootDirectoryClusterCount) {
            markAllocated(bytes, cluster)
        }
        return bytes
    }

    fun upcaseTable(layout: ExFatLayout = layout()): ByteArray =
        ByteArray((layout.upcaseClusterCount * layout.clusterSizeBytes).toInt()).also { bytes ->
            for (codePoint in 0..0xffff) {
                val mapped = codePoint.toChar().uppercaseChar().code
                putLeShort(bytes, codePoint * 2, mapped)
            }
        }

    fun rootDirectory(layout: ExFatLayout = layout()): ByteArray =
        ByteArray((layout.rootDirectoryClusterCount * layout.clusterSizeBytes).toInt()).also { bytes ->
            writeAllocationBitmapEntry(bytes, 0, layout)
            writeUpcaseEntry(bytes, 32, layout, upcaseChecksum(upcaseTable(layout), layout.upcaseLength))
            writeVolumeLabelEntry(bytes, 64)
        }

    fun bootRegionWithoutChecksum(layout: ExFatLayout = layout()): ByteArray =
        ByteArray(layout.sectorSize * 11).also { region ->
            mainBootSector(layout).copyInto(region, 0)
            val extended = extendedBootSector(layout)
            for (sector in 1..8) extended.copyInto(region, sector * layout.sectorSize)
        }

    private fun writeAllocationBitmapEntry(bytes: ByteArray, offset: Int, layout: ExFatLayout) {
        bytes[offset] = 0x81.toByte()
        bytes[offset + 1] = 0
        putLeInt(bytes, offset + 20, layout.bitmapFirstCluster.toLong())
        putLeLong(bytes, offset + 24, layout.bitmapLength)
    }

    private fun writeUpcaseEntry(bytes: ByteArray, offset: Int, layout: ExFatLayout, checksum: Long) {
        bytes[offset] = 0x82.toByte()
        putLeInt(bytes, offset + 4, checksum)
        putLeInt(bytes, offset + 20, layout.upcaseFirstCluster.toLong())
        putLeLong(bytes, offset + 24, layout.upcaseLength)
    }

    private fun writeVolumeLabelEntry(bytes: ByteArray, offset: Int) {
        val normalized = label.uppercase()
            .filter { it.code in 0x20..0x7e }
            .take(11)
        bytes[offset] = 0x83.toByte()
        bytes[offset + 1] = normalized.length.toByte()
        val labelBytes = normalized.toByteArray(Charsets.UTF_16LE)
        labelBytes.copyInto(bytes, offset + 2)
    }

    private fun putClusterChain(fatSector: ByteArray, firstCluster: Int, clusterCount: Int) {
        for (index in 0 until clusterCount) {
            val cluster = firstCluster + index
            val next = if (index == clusterCount - 1) 0xffffffffL else (cluster + 1).toLong()
            val offset = cluster * 4
            require(offset + 4 <= fatSector.size) {
                "Initial exFAT metadata cluster chain exceeded first FAT sector."
            }
            putLeInt(fatSector, offset, next)
        }
    }

    private fun markAllocated(bitmap: ByteArray, cluster: Int) {
        val bitmapIndex = cluster - FIRST_CLUSTER
        val byteIndex = bitmapIndex / 8
        val bit = bitmapIndex % 8
        bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl bit)).toByte()
    }

    private fun chooseSectorsPerCluster(totalSectors: Long, sectorSize: Int): Int {
        val volumeBytes = totalSectors * sectorSize.toLong()
        val desiredClusterBytes = when {
            volumeBytes <= 256L * 1024L * 1024L -> 4L * 1024L
            volumeBytes <= 32L * 1024L * 1024L * 1024L -> 32L * 1024L
            volumeBytes <= 256L * 1024L * 1024L * 1024L -> 128L * 1024L
            else -> 256L * 1024L
        }
        val minClusterBytes = maxOf(4L * 1024L, sectorSize.toLong())
        var sectors = (maxOf(desiredClusterBytes, minClusterBytes) / sectorSize).toInt().coerceAtLeast(1)
        sectors = sectors.nextPowerOfTwo()

        val maxSectors = 1 shl (25 - sectorSize.log2())
        while (sectors <= maxSectors) {
            val clusterCount = estimateClusterCount(totalSectors, sectorSize, sectors)
            if (clusterCount in 17..EXFAT_RECOMMENDED_MAX_CLUSTERS) return sectors
            sectors *= 2
        }
        error("Unable to choose exFAT cluster size for $totalSectors sectors")
    }

    private fun estimateClusterCount(totalSectors: Long, sectorSize: Int, sectorsPerCluster: Int): Long {
        var fatLength = 1L
        repeat(16) {
            val clusterHeapOffset = alignUp(FAT_OFFSET + fatLength, sectorsPerCluster.toLong())
            val clusterCount = (totalSectors - clusterHeapOffset) / sectorsPerCluster.toLong()
            val requiredFat = ceil(((clusterCount + 2L) * 4L).toDouble() / sectorSize.toDouble()).toLong()
            if (requiredFat == fatLength) return clusterCount
            fatLength = requiredFat
        }
        val clusterHeapOffset = alignUp(FAT_OFFSET + fatLength, sectorsPerCluster.toLong())
        return (totalSectors - clusterHeapOffset) / sectorsPerCluster.toLong()
    }

    private fun volumeSerial(layout: ExFatLayout): Int =
        0x52460000 or
            ((layout.sectorsPerCluster and 0xff) shl 8) or
            (layout.bytesPerSectorShift and 0xff)

    companion object {
        const val FAT_OFFSET = 24L
        const val FIRST_CLUSTER = 2
        const val UPCASE_TABLE_BYTES = 65536 * 2
        const val EXFAT_RECOMMENDED_MAX_CLUSTERS = 0x00fffffeL

        fun bootChecksum(regionWithoutChecksum: ByteArray): Long {
            var checksum = 0L
            for (index in regionWithoutChecksum.indices) {
                if (index == 106 || index == 107 || index == 112) continue
                checksum = (((if ((checksum and 1L) != 0L) 0x80000000L else 0L) + (checksum ushr 1) +
                    (regionWithoutChecksum[index].toLong() and 0xffL)) and 0xffffffffL)
            }
            return checksum
        }

        fun upcaseChecksum(table: ByteArray, length: Long): Long {
            var checksum = 0L
            for (index in 0 until length.toInt()) {
                checksum = (((if ((checksum and 1L) != 0L) 0x80000000L else 0L) + (checksum ushr 1) +
                    (table[index].toLong() and 0xffL)) and 0xffffffffL)
            }
            return checksum
        }
    }
}

class ExFatFormatter(
    private val blockDevice: SeekableBlockDevice,
) {
    fun format(
        partitionPlan: PartitionPlan,
        label: String = RecoveryVolumeLabel.DEFAULT_LABEL,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ): ExFatLayout {
        require(partitionPlan.fileSystemType == FileSystemType.ExFat)
        require(partitionPlan.sectorSize == blockDevice.blockSize) {
            "exFAT formatter currently requires sector size to match device block size."
        }

        val builder = ExFatVolumeBuilder(partitionPlan, label)
        val layout = builder.layout()
        val totalWrites = 24L +
            layout.fatLength +
            layout.bitmapClusterCount.toLong() * layout.sectorsPerCluster +
            layout.upcaseClusterCount.toLong() * layout.sectorsPerCluster +
            layout.rootDirectoryClusterCount.toLong() * layout.sectorsPerCluster
        var writes = 0L

        fun writeSector(absoluteSector: Long, bytes: ByteArray) {
            cancellationToken.throwIfCancelled()
            blockDevice.seek(absoluteSector * layout.sectorSize.toLong())
            blockDevice.write(bytes, 0, bytes.size)
            writes += bytes.size / layout.sectorSize
            onProgress(Progress(writes, totalWrites, Progress.Phase.Writing))
        }

        fun writeCluster(firstCluster: Int, bytes: ByteArray) {
            val absoluteSector = clusterAbsoluteSector(partitionPlan, layout, firstCluster)
            var offset = 0
            var sector = absoluteSector
            while (offset < bytes.size) {
                writeSector(sector, bytes.copyOfRange(offset, offset + layout.sectorSize))
                offset += layout.sectorSize
                sector++
            }
        }

        val bootRegion = builder.bootRegionWithoutChecksum(layout)
        val checksumSector = builder.bootChecksumSector(layout)
        for (sector in 0 until 11) {
            writeSector(partitionPlan.startSector + sector, bootRegion.copyOfRange(sector * layout.sectorSize, (sector + 1) * layout.sectorSize))
        }
        writeSector(partitionPlan.startSector + 11, checksumSector)
        for (sector in 0 until 11) {
            writeSector(partitionPlan.startSector + 12 + sector, bootRegion.copyOfRange(sector * layout.sectorSize, (sector + 1) * layout.sectorSize))
        }
        writeSector(partitionPlan.startSector + 23, checksumSector)

        val firstFatSector = builder.fatSector(layout)
        val zero = ByteArray(layout.sectorSize)
        for (sector in 0 until layout.fatLength) {
            writeSector(partitionPlan.startSector + layout.fatOffset + sector, if (sector == 0L) firstFatSector else zero)
        }

        writeCluster(layout.bitmapFirstCluster, builder.allocationBitmap(layout))
        writeCluster(layout.upcaseFirstCluster, builder.upcaseTable(layout))
        writeCluster(layout.rootDirectoryFirstCluster, builder.rootDirectory(layout))

        blockDevice.flush()
        onProgress(Progress(totalWrites, totalWrites, Progress.Phase.Finished))
        return layout
    }

    companion object {
        fun clusterAbsoluteSector(partitionPlan: PartitionPlan, layout: ExFatLayout, cluster: Int): Long =
            partitionPlan.startSector + layout.clusterHeapOffset +
                (cluster.toLong() - ExFatVolumeBuilder.FIRST_CLUSTER.toLong()) * layout.sectorsPerCluster.toLong()
    }
}

private fun putAscii(bytes: ByteArray, offset: Int, value: String) {
    value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
}

private fun putLeShort(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun putLeInt(bytes: ByteArray, offset: Int, value: Long) {
    val intValue = value.toInt()
    bytes[offset] = intValue.toByte()
    bytes[offset + 1] = (intValue ushr 8).toByte()
    bytes[offset + 2] = (intValue ushr 16).toByte()
    bytes[offset + 3] = (intValue ushr 24).toByte()
}

private fun putLeLong(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) {
        bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}

private fun alignUp(value: Long, alignment: Long): Long =
    if (value % alignment == 0L) value else value + (alignment - (value % alignment))

private fun Int.isPowerOfTwo(): Boolean =
    this > 0 && (this and (this - 1)) == 0

private fun Int.log2(): Int {
    require(isPowerOfTwo())
    return Integer.numberOfTrailingZeros(this)
}

private fun Int.nextPowerOfTwo(): Int {
    var value = 1
    while (value < this) value = value shl 1
    return value
}
