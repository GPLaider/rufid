package io.github.rufid.core

import io.github.rufid.format.Fat32Layout
import io.github.rufid.format.Fat32VolumeBuilder
import io.github.rufid.format.UsbRecoveryPlanner
import io.github.rufid.partition.FileSystemType
import io.github.rufid.partition.MbrTable
import io.github.rufid.partition.PartitionPlan
import io.github.rufid.windows.WindowsIsoPlan

interface WimSplitStrategy {
    fun splitInstallWim(
        source: ExtractableIsoFile,
        cancellationToken: CancellationToken = CancellationToken.None,
    ): List<ExtractableIsoFile>

    fun cleanup() = Unit
}

object UnsupportedWimSplitStrategy : WimSplitStrategy {
    override fun splitInstallWim(
        source: ExtractableIsoFile,
        cancellationToken: CancellationToken,
    ): List<ExtractableIsoFile> {
        cancellationToken.throwIfCancelled()
        throw UnsupportedOperationException("install.wim split required; packaged WIM-aware split engine is unavailable.")
    }
}

class WindowsIsoExtractor(
    private val blockDevice: SeekableBlockDevice,
    private val wimSplitStrategy: WimSplitStrategy = UnsupportedWimSplitStrategy,
) {
    fun write(
        source: SeekableByteSource,
        imageName: String,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) {
        val files = IsoImageReader.listFiles(source)
        WindowsIsoFileSetExtractor(blockDevice, wimSplitStrategy).write(files, imageName, cancellationToken, onProgress)
    }
}

class WindowsIsoFileSetExtractor(
    private val blockDevice: SeekableBlockDevice,
    private val wimSplitStrategy: WimSplitStrategy = UnsupportedWimSplitStrategy,
) {
    fun write(
        files: List<ExtractableIsoFile>,
        imageName: String,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) {
        val plan = IsoExtractionPlanner.plan(imageName, files.map { IsoFileEntry(it.path, it.size) })
        require(plan.support == IsoExtractionSupport.WindowsInstaller) {
            "ISO extraction is only enabled for Windows installer ISO images."
        }
        var writableFiles = files
        try {
            writableFiles = if (plan.requiresWimSplit) {
                replaceInstallWimWithSplitSet(files, cancellationToken)
            } else {
                files
            }
            val tooLarge = writableFiles.firstOrNull { it.size > WindowsIsoPlan.FAT32_MAX_FILE_SIZE }
            if (tooLarge != null) {
                throw UnsupportedOperationException("${tooLarge.path} exceeds FAT32 file size limit.")
            }

            val recoveryPlan = UsbRecoveryPlanner.create(blockDevice.sizeBytes, blockDevice.blockSize, FileSystemType.Fat32)
            val builder = Fat32VolumeBuilder(recoveryPlan.partitionPlan, label = WINDOWS_VOLUME_LABEL)
            val layout = builder.layout()
            val treeWriter = Fat32IsoTreeWriter(
                blockDevice,
                recoveryPlan.partitionPlan,
                layout,
                volumeLabel = WINDOWS_VOLUME_LABEL,
            )
            treeWriter.prepare(writableFiles)
            cancellationToken.throwIfCancelled()
            writeMbr(recoveryPlan.partitionPlan)
            val metadataWriter = Fat32MetadataWriter(blockDevice, recoveryPlan.partitionPlan, builder, layout)
            metadataWriter.format(cancellationToken)
            treeWriter.writePrepared(writableFiles, cancellationToken, onProgress)
            metadataWriter.updateFsInfo(
                freeClusterCount = treeWriter.freeClusterCount,
                nextFreeCluster = treeWriter.nextFreeClusterHint,
                cancellationToken = cancellationToken,
            )
            blockDevice.flush()
            onProgress(Progress(writableFiles.sumOf { it.size }, writableFiles.sumOf { it.size }, Progress.Phase.Finished))
        } finally {
            writableFiles.forEach { it.close() }
            wimSplitStrategy.cleanup()
        }
    }

    private fun replaceInstallWimWithSplitSet(
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
    ): List<ExtractableIsoFile> {
        val installWim = files.firstOrNull { it.path.normalizeIsoPath() == "sources/install.wim" }
            ?: throw UnsupportedOperationException("install.wim split required, but sources/install.wim was not found.")
        val splitFiles = wimSplitStrategy.splitInstallWim(installWim, cancellationToken)
        require(splitFiles.isNotEmpty()) { "WIM split strategy returned no .swm files." }
        require(splitFiles.none { it.path.normalizeIsoPath() == "sources/install.wim" }) {
            "WIM split strategy must replace install.wim with .swm files."
        }
        return files.filterNot { it.path.normalizeIsoPath() == "sources/install.wim" } + splitFiles
    }

    private fun writeMbr(partitionPlan: PartitionPlan) {
        val mbr = ByteArray(blockDevice.blockSize)
        MbrTable(partitionPlan).toBytes().copyInto(mbr)
        blockDevice.seek(0)
        blockDevice.write(mbr, 0, mbr.size)
    }

    private companion object {
        const val WINDOWS_VOLUME_LABEL = "WININSTALL"
    }
}

private class Fat32MetadataWriter(
    private val blockDevice: SeekableBlockDevice,
    private val partitionPlan: PartitionPlan,
    private val builder: Fat32VolumeBuilder,
    private val layout: Fat32Layout,
) {
    fun format(cancellationToken: CancellationToken) {
        val boot = builder.bootSector(layout)
        val fsInfo = builder.fsInfoSector(layout)
        writeZeroSectors(0, layout.reservedSectors.toLong(), cancellationToken)
        writeRelativeSector(0, boot, cancellationToken)
        writeRelativeSector(1, fsInfo, cancellationToken)
        writeRelativeSector(6, boot, cancellationToken)
        writeRelativeSector(7, fsInfo, cancellationToken)
        val rootStart = layout.reservedSectors.toLong() + layout.fatCount.toLong() * layout.sectorsPerFat
        writeZeroSectors(rootStart, layout.sectorsPerCluster.toLong(), cancellationToken)
    }

    fun updateFsInfo(
        freeClusterCount: Long,
        nextFreeCluster: Long,
        cancellationToken: CancellationToken,
    ) {
        val fsInfo = builder.fsInfoSector(layout, freeClusterCount, nextFreeCluster)
        writeRelativeSector(1, fsInfo, cancellationToken)
        writeRelativeSector(7, fsInfo, cancellationToken)
    }

    private fun writeZeroSectors(relativeStartSector: Long, sectorCount: Long, cancellationToken: CancellationToken) {
        val zeroChunk = ByteArray(layout.sectorSize * ZERO_CHUNK_SECTORS)
        var sector = relativeStartSector
        var remaining = sectorCount
        while (remaining > 0L) {
            cancellationToken.throwIfCancelled()
            val chunkSectors = minOf(ZERO_CHUNK_SECTORS.toLong(), remaining).toInt()
            blockDevice.seek((partitionPlan.startSector + sector) * layout.sectorSize.toLong())
            blockDevice.write(zeroChunk, 0, chunkSectors * layout.sectorSize)
            sector += chunkSectors
            remaining -= chunkSectors
        }
    }

    private fun writeRelativeSector(relativeSector: Long, bytes: ByteArray, cancellationToken: CancellationToken) {
        cancellationToken.throwIfCancelled()
        blockDevice.seek((partitionPlan.startSector + relativeSector) * layout.sectorSize.toLong())
        blockDevice.write(bytes, 0, bytes.size)
    }

    private companion object {
        const val ZERO_CHUNK_SECTORS = 128
    }
}

internal fun String.normalizeIsoPath(): String =
    replace('\\', '/').trim().trimStart('/').lowercase()
