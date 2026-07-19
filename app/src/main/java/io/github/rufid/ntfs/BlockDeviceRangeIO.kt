package io.github.rufid.ntfs

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.SeekableBlockDevice
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/** Stream file <-> block-device range helpers (full-range and GPT-safe invalidation). */
object BlockDeviceRangeIO {
    /** Protective GPT: MBR + primary header + 32 entry sectors. */
    const val GPT_PRIMARY_WIPE_SECTORS: Int = 34

    /** Backup entry array (32) + backup header (1). */
    const val GPT_BACKUP_WIPE_SECTORS: Int = 33

    fun writeFileToRange(
        blockDevice: SeekableBlockDevice,
        deviceByteOffset: Long,
        source: File,
        length: Long,
        cancellationToken: CancellationToken = CancellationToken.None,
        chunkSize: Int = 256 * 1024,
    ) {
        require(length >= 0)
        require(deviceByteOffset >= 0)
        if (source.length() < length) {
            throw IOException("Source shorter than declared length: ${source.length()} < $length")
        }
        RandomAccessFile(source, "r").use { raf ->
            val buffer = ByteArray(chunkSize)
            var done = 0L
            while (done < length) {
                cancellationToken.throwIfCancelled()
                val n = minOf(chunkSize.toLong(), length - done).toInt()
                raf.seek(done)
                raf.readFully(buffer, 0, n)
                blockDevice.seek(deviceByteOffset + done)
                blockDevice.write(buffer, 0, n)
                done += n
            }
        }
        blockDevice.flush()
    }

    fun compareFileToRange(
        blockDevice: SeekableBlockDevice,
        deviceByteOffset: Long,
        source: File,
        length: Long,
        cancellationToken: CancellationToken = CancellationToken.None,
        chunkSize: Int = 256 * 1024,
    ) {
        RandomAccessFile(source, "r").use { raf ->
            val expected = ByteArray(chunkSize)
            val actual = ByteArray(chunkSize)
            var done = 0L
            while (done < length) {
                cancellationToken.throwIfCancelled()
                val n = minOf(chunkSize.toLong(), length - done).toInt()
                raf.seek(done)
                raf.readFully(expected, 0, n)
                blockDevice.seek(deviceByteOffset + done)
                var got = 0
                while (got < n) {
                    val r = blockDevice.read(actual, got, n - got)
                    if (r <= 0) {
                        throw IOException(
                            "Short read during USB range compare at offset ${deviceByteOffset + done + got}.",
                        )
                    }
                    got += r
                }
                for (i in 0 until n) {
                    if (expected[i] != actual[i]) {
                        throw IOException(
                            "USB range byte mismatch at device offset ${deviceByteOffset + done + i}.",
                        )
                    }
                }
                done += n
            }
        }
    }

    /**
     * Wipe partition metadata so a failed GPT write cannot leave a bootable table.
     * Fully zeros the first [GPT_PRIMARY_WIPE_SECTORS] and last [GPT_BACKUP_WIPE_SECTORS]
     * logical sectors (primary GPT region and backup GPT region).
     */
    fun invalidatePartitionMetadata(device: SeekableBlockDevice) {
        val sectorBytes = device.blockSize
        require(sectorBytes > 0)
        val totalSectors = device.sizeBytes / sectorBytes
        if (totalSectors <= 0L) {
            throw IOException("Device has no addressable sectors for invalidation.")
        }
        val emptySector = ByteArray(sectorBytes)
        val primaryCount = min(GPT_PRIMARY_WIPE_SECTORS.toLong(), totalSectors)
        var lba = 0L
        while (lba < primaryCount) {
            device.seek(lba * sectorBytes)
            device.write(emptySector, 0, sectorBytes)
            lba++
        }
        if (totalSectors > primaryCount) {
            val backupCount = min(GPT_BACKUP_WIPE_SECTORS.toLong(), totalSectors)
            val backupStart = totalSectors - backupCount
            lba = backupStart
            while (lba < totalSectors) {
                device.seek(lba * sectorBytes)
                device.write(emptySector, 0, sectorBytes)
                lba++
            }
        }
        device.flush()
    }
}
