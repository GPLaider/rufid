package io.github.rufid.ntfs

import io.github.rufid.core.BootMediaInspector
import io.github.rufid.core.CancellationToken
import io.github.rufid.core.ExtractableIsoFile
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.partition.UefiNtfsHelperWriter
import io.github.rufid.partition.UefiNtfsLayout
import io.github.rufid.partition.UefiNtfsPartitionTableMode
import java.io.File
import java.io.IOException

/**
 * UEFI:NTFS Windows ISO writer transaction (layout + NTFS content).
 *
 * Order:
 * 1) plan layout
 * 2) build+verify sparse NTFS image in cache (before USB touch of tables)
 * 3) invalidate partition metadata (primary+backup GPT regions)
 * 4) stream **allocated** sparse extents to data partition; compare extents
 * 5) write helper + MBR/GPT last
 * 6) post-publish inspect: NTFS VBR at data LBA
 *
 * Any error/cancel must not report success; incomplete images deleted; tables left invalid.
 * install.wim is kept unsplit.
 */
class UefiNtfsWindowsIsoWriter(
    private val blockDevice: SeekableBlockDevice,
    private val imageBuilder: SparseNtfsImageBuilder,
    private val helperImage: ByteArray,
    private val cacheDir: File,
    private val mode: UefiNtfsPartitionTableMode,
) {
    data class Result(
        val layout: UefiNtfsLayout,
        val imageBytes: Long,
        val fileCount: Int,
    )

    fun write(
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        require(files.isNotEmpty()) { "No files to write into NTFS volume." }
        val totalPayload = files.sumOf { it.size }
        val helperWriter = UefiNtfsHelperWriter(blockDevice)
        val layout = helperWriter.plan(helperImage, mode)
        val dataBytes = layout.dataSectorCount * layout.sectorSize.toLong()
        val needed = NtfsImageSizing.requiredImageBytes(totalPayload)
        if (needed > dataBytes) {
            throw IOException(
                "USB data partition too small for NTFS Windows content: need~$needed have $dataBytes.",
            )
        }

        var built: SparseNtfsImageBuilder.BuiltImage? = null
        var usbMutationStarted = false
        try {
            cancellationToken.throwIfCancelled()
            built = imageBuilder.buildAndVerify(
                cacheDir = cacheDir,
                sizeBytes = dataBytes,
                files = files,
                cancellationToken = cancellationToken,
                requiredSourceBytes = totalPayload,
                onProgress = onProgress,
            )
            cancellationToken.throwIfCancelled()

            usbMutationStarted = true
            BlockDeviceRangeIO.invalidatePartitionMetadata(blockDevice)
            cancellationToken.throwIfCancelled()

            val dataOffset = layout.dataStartSector * layout.sectorSize.toLong()
            SparseBlockDeviceIO.writeAllocatedExtents(
                blockDevice = blockDevice,
                deviceByteOffset = dataOffset,
                source = built.imageFile,
                logicalLength = built.sizeBytes,
                cancellationToken = cancellationToken,
                onProgress = { done, total ->
                    onProgress(Progress(done, total, Progress.Phase.CopyingSparse))
                },
            )
            SparseBlockDeviceIO.compareAllocatedExtents(
                blockDevice = blockDevice,
                deviceByteOffset = dataOffset,
                source = built.imageFile,
                logicalLength = built.sizeBytes,
                cancellationToken = cancellationToken,
                onProgress = { done, total ->
                    onProgress(Progress(done, total, Progress.Phase.ComparingSparse))
                },
            )
            cancellationToken.throwIfCancelled()

            helperWriter.write(
                helperImage = helperImage,
                mode = mode,
                cancellationToken = cancellationToken,
            )

            // Final post-publish inspection: NTFS VBR at selected data LBA.
            val inspection = BootMediaInspector(blockDevice).inspect()
            if (inspection.bootSector.lba != layout.dataStartSector) {
                throw IOException(
                    "Post-publish inspection boot LBA ${inspection.bootSector.lba} " +
                        "!= data start ${layout.dataStartSector}.",
                )
            }
            if (inspection.bootSector.fileSystem != "NTFS" && !inspection.looksLikeNtfsVolume) {
                throw IOException(
                    "Post-publish inspection did not see NTFS VBR at LBA ${layout.dataStartSector}.",
                )
            }

            onProgress(Progress(totalPayload, totalPayload, Progress.Phase.Finished))
            return Result(layout = layout, imageBytes = built.sizeBytes, fileCount = files.size)
        } catch (error: Exception) {
            if (usbMutationStarted) {
                try {
                    BlockDeviceRangeIO.invalidatePartitionMetadata(blockDevice)
                } catch (invalidationError: Exception) {
                    error.addSuppressed(invalidationError)
                }
            }
            throw error
        } finally {
            built?.imageFile?.let { imageBuilder.deleteImage(it) }
        }
    }
}
