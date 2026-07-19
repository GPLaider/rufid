package io.github.rufid.ntfs

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Allocated (data) extents of a sparse file via SEEK_DATA / SEEK_HOLE.
 * When the host does not support those seeks, returns a single full-range extent
 * covering [0, logicalLength) so callers still make progress (explicit fallback).
 */
object SparseFileExtents {
    data class Extent(
        val offset: Long,
        val length: Long,
    ) {
        init {
            require(offset >= 0 && length > 0)
        }
    }

    /** SEEK_DATA / SEEK_HOLE constants (Linux / Android). */
    const val SEEK_DATA: Int = 3
    const val SEEK_HOLE: Int = 4

    /**
     * List allocated byte ranges in [0, logicalLength).
     * Never returns empty when logicalLength > 0: uses full-range fallback if needed.
     */
    fun listAllocatedExtents(file: File, logicalLength: Long): List<Extent> {
        require(logicalLength >= 0)
        if (logicalLength == 0L) return emptyList()
        val listed = runCatching { listViaSeekDataHole(file, logicalLength) }.getOrNull()
        if (listed != null && listed.isNotEmpty()) {
            return listed
        }
        // Explicit full-range fallback when SEEK_DATA/SEEK_HOLE unsupported or empty.
        return listOf(Extent(0L, logicalLength))
    }

    private fun listViaSeekDataHole(file: File, logicalLength: Long): List<Extent>? {
        RandomAccessFile(file, "r").use { raf ->
            val fd = raf.fd
            // Prefer Android Os when present; else Linux libc lseek via reflection is not portable.
            val seeker = androidOsSeeker() ?: return null
            val extents = ArrayList<Extent>()
            var pos = 0L
            while (pos < logicalLength) {
                val dataStart = try {
                    seeker(fd, pos, SEEK_DATA)
                } catch (_: Exception) {
                    return null
                }
                if (dataStart < 0L || dataStart >= logicalLength) {
                    break
                }
                val holeStart = try {
                    seeker(fd, dataStart, SEEK_HOLE)
                } catch (_: Exception) {
                    return null
                }
                val end = when {
                    holeStart < 0 -> logicalLength
                    holeStart > logicalLength -> logicalLength
                    holeStart <= dataStart -> logicalLength
                    else -> holeStart
                }
                val len = end - dataStart
                if (len > 0) {
                    extents += Extent(dataStart, len)
                }
                if (end <= pos) {
                    // Avoid infinite loop on broken seek implementations.
                    return null
                }
                pos = end
            }
            return extents
        }
    }

    /**
     * Returns a seek function (fd, offset, whence) -> newOffset, or null if unavailable.
     * Uses android.system.Os when on Android; otherwise null (caller uses full-range fallback).
     */
    private fun androidOsSeeker(): ((java.io.FileDescriptor, Long, Int) -> Long)? {
        return try {
            val osClass = Class.forName("android.system.Os")
            val lseekMethod = osClass.getMethod(
                "lseek",
                java.io.FileDescriptor::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val seekFn: (java.io.FileDescriptor, Long, Int) -> Long = { fd, offset, whence ->
                lseekMethod.invoke(null, fd, offset, whence) as Long
            }
            seekFn
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    fun totalAllocatedBytes(extents: List<Extent>): Long =
        extents.sumOf { it.length }
}

/**
 * Copy/compare only allocated sparse extents from [source] into a block-device range.
 */
object SparseBlockDeviceIO {
    fun writeAllocatedExtents(
        blockDevice: io.github.rufid.core.SeekableBlockDevice,
        deviceByteOffset: Long,
        source: File,
        logicalLength: Long,
        cancellationToken: io.github.rufid.core.CancellationToken =
            io.github.rufid.core.CancellationToken.None,
        chunkSize: Int = 256 * 1024,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        require(logicalLength >= 0 && deviceByteOffset >= 0)
        if (source.length() < logicalLength && source.length() > 0) {
            // Sparse logical size may exceed reported length on some platforms; prefer max.
        }
        val extents = SparseFileExtents.listAllocatedExtents(source, logicalLength)
        val total = SparseFileExtents.totalAllocatedBytes(extents).coerceAtLeast(1L)
        var done = 0L
        onProgress(0L, total)
        java.io.RandomAccessFile(source, "r").use { raf ->
            val buffer = ByteArray(chunkSize)
            extents.forEach { extent ->
                var remaining = extent.length
                var fileOff = extent.offset
                while (remaining > 0) {
                    cancellationToken.throwIfCancelled()
                    val n = minOf(chunkSize.toLong(), remaining).toInt()
                    raf.seek(fileOff)
                    raf.readFully(buffer, 0, n)
                    blockDevice.seek(deviceByteOffset + fileOff)
                    blockDevice.write(buffer, 0, n)
                    fileOff += n
                    remaining -= n
                    done += n
                    onProgress(done, total)
                }
            }
        }
        blockDevice.flush()
    }

    fun compareAllocatedExtents(
        blockDevice: io.github.rufid.core.SeekableBlockDevice,
        deviceByteOffset: Long,
        source: File,
        logicalLength: Long,
        cancellationToken: io.github.rufid.core.CancellationToken =
            io.github.rufid.core.CancellationToken.None,
        chunkSize: Int = 256 * 1024,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val extents = SparseFileExtents.listAllocatedExtents(source, logicalLength)
        val total = SparseFileExtents.totalAllocatedBytes(extents).coerceAtLeast(1L)
        var done = 0L
        onProgress(0L, total)
        java.io.RandomAccessFile(source, "r").use { raf ->
            val expected = ByteArray(chunkSize)
            val actual = ByteArray(chunkSize)
            extents.forEach { extent ->
                var remaining = extent.length
                var fileOff = extent.offset
                while (remaining > 0) {
                    cancellationToken.throwIfCancelled()
                    val n = minOf(chunkSize.toLong(), remaining).toInt()
                    raf.seek(fileOff)
                    raf.readFully(expected, 0, n)
                    blockDevice.seek(deviceByteOffset + fileOff)
                    var read = 0
                    while (read < n) {
                        val r = blockDevice.read(actual, read, n - read)
                        if (r <= 0) {
                            throw IOException(
                                "Short read during sparse compare at device offset " +
                                    "${deviceByteOffset + fileOff + read}.",
                            )
                        }
                        read += r
                    }
                    for (i in 0 until n) {
                        if (expected[i] != actual[i]) {
                            throw IOException(
                                "Sparse USB range byte mismatch at device offset " +
                                    "${deviceByteOffset + fileOff + i}.",
                            )
                        }
                    }
                    fileOff += n
                    remaining -= n
                    done += n
                    onProgress(done, total)
                }
            }
        }
    }
}
