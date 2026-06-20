package io.github.rufid.core

import java.io.IOException
import java.io.InputStream

class RawImageWriter(
    private val blockDevice: SeekableBlockDevice,
    bufferSize: Int = 1024 * 1024,
) {
    private val alignedBufferSize = bufferSize - (bufferSize % blockDevice.blockSize)

    fun write(
        image: InputStream,
        imageSize: Long,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ) {
        require(imageSize >= 0L)
        require(alignedBufferSize > 0) { "Buffer must fit at least one block." }

        val buffer = ByteArray(alignedBufferSize)
        var written = 0L

        blockDevice.seek(0)
        while (true) {
            cancellationToken.throwIfCancelled()
            val read = readChunk(image, buffer)
            if (read == 0) break
            if (imageSize > 0L && written + read > imageSize) {
                throw IOException("Input exceeded expected image size: expected=$imageSize actual>${written + read}")
            }

            val writeLength = if (read % blockDevice.blockSize == 0) {
                read
            } else {
                val padded = read + (blockDevice.blockSize - (read % blockDevice.blockSize))
                buffer.fill(0.toByte(), read, padded)
                padded
            }
            if (written + writeLength > blockDevice.sizeBytes) {
                throw IOException("Write would exceed target device capacity: target=${blockDevice.sizeBytes} requested=${written + writeLength}")
            }

            blockDevice.write(buffer, 0, writeLength)
            written += read
            cancellationToken.throwIfCancelled()
            onProgress(Progress(written, imageSize, Progress.Phase.Writing))
        }

        if (imageSize > 0L && written != imageSize) {
            throw IOException("Input ended before expected image size: expected=$imageSize actual=$written")
        }

        blockDevice.flush()
        onProgress(Progress(written, imageSize, Progress.Phase.Finished))
    }

    private fun readChunk(image: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = image.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
