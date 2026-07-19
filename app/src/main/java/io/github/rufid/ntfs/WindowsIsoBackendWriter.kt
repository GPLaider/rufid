package io.github.rufid.ntfs

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.IsoImageReader
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.core.SeekableByteSource
import io.github.rufid.core.WindowsIsoExtractor
import io.github.rufid.core.WimSplitStrategy
import io.github.rufid.partition.UefiNtfsPartitionTableMode
import java.io.File

/**
 * End-to-end callable Windows ISO backend (stage 4B).
 * Explicit mode: FAT32 extraction vs NTFS+UEFI:NTFS (MBR/GPT).
 * UI redesign is stage 6.
 */
class WindowsIsoBackendWriter(
    private val blockDevice: SeekableBlockDevice,
    private val mode: WindowsInstallBackendMode,
    private val imageBuilder: SparseNtfsImageBuilder? = null,
    private val helperImage: ByteArray? = null,
    private val cacheDir: File? = null,
    private val wimSplitStrategy: WimSplitStrategy = io.github.rufid.core.UnsupportedWimSplitStrategy,
) {
    fun write(
        source: SeekableByteSource,
        imageName: String,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit = {},
    ) {
        when (mode) {
            WindowsInstallBackendMode.Fat32Extraction -> {
                WindowsIsoExtractor(blockDevice, wimSplitStrategy).write(
                    source = source,
                    imageName = imageName,
                    cancellationToken = cancellationToken,
                    onProgress = onProgress,
                )
            }
            WindowsInstallBackendMode.NtfsUefiMbr,
            WindowsInstallBackendMode.NtfsUefiGpt,
            -> {
                val builder = checkNotNull(imageBuilder) { "SparseNtfsImageBuilder required for NTFS backend." }
                val helper = checkNotNull(helperImage) { "UEFI:NTFS helper image required for NTFS backend." }
                val cache = checkNotNull(cacheDir) { "cacheDir required for NTFS backend." }
                val tableMode = mode.partitionTableMode
                    ?: UefiNtfsPartitionTableMode.Mbr
                val files = IsoImageReader.listFiles(source)
                try {
                    // NTFS keeps install.wim unsplit; do not invoke WIM split.
                    UefiNtfsWindowsIsoWriter(
                        blockDevice = blockDevice,
                        imageBuilder = builder,
                        helperImage = helper,
                        cacheDir = cache,
                        mode = tableMode,
                    ).write(files, cancellationToken, onProgress)
                } finally {
                    files.forEach { it.close() }
                }
            }
        }
    }
}
