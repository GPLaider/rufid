package io.github.rufid.core

import java.io.OutputStream

class BlockDeviceBackup(
    private val blockDevice: SeekableBlockDevice,
    bufferSize: Int = 1024 * 1024,
) {
    private val alignedBufferSize = bufferSize - (bufferSize % blockDevice.blockSize)

    fun backup(
        output: OutputStream,
        byteCount: Long = blockDevice.sizeBytes,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) {
        require(byteCount >= 0)
        require(alignedBufferSize > 0)

        val buffer = ByteArray(alignedBufferSize)
        var copied = 0L

        blockDevice.seek(0)
        while (copied < byteCount) {
            cancellationToken.throwIfCancelled()
            val toRead = minOf(buffer.size.toLong(), byteCount - copied).toInt()
            val readLength = toRead.roundUpToBlock(blockDevice.blockSize)
            blockDevice.read(buffer, 0, readLength)
            output.write(buffer, 0, toRead)
            copied += toRead
            cancellationToken.throwIfCancelled()
            onProgress(Progress(copied, byteCount, Progress.Phase.Writing))
        }

        output.flush()
        onProgress(Progress(copied, byteCount, Progress.Phase.Finished))
    }

    private fun Int.roundUpToBlock(blockSize: Int): Int =
        if (this % blockSize == 0) this else this + (blockSize - (this % blockSize))
}
