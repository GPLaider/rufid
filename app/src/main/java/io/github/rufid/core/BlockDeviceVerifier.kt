package io.github.rufid.core

import java.io.InputStream

data class VerifyResult(
    val matched: Boolean,
    val checkedBytes: Long,
    val mismatchOffset: Long? = null,
)

class BlockDeviceVerifier(
    private val blockDevice: SeekableBlockDevice,
    bufferSize: Int = 1024 * 1024,
) {
    private val alignedBufferSize = bufferSize - (bufferSize % blockDevice.blockSize)

    fun verify(
        image: InputStream,
        imageSize: Long,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ): VerifyResult {
        require(alignedBufferSize > 0)

        val expected = ByteArray(alignedBufferSize)
        val actual = ByteArray(alignedBufferSize)
        var checked = 0L

        blockDevice.seek(0)
        while (checked < imageSize) {
            cancellationToken.throwIfCancelled()
            val expectedLength = readChunk(image, expected, minOf(expected.size.toLong(), imageSize - checked).toInt())
            if (expectedLength == 0) break

            val readLength = expectedLength.roundUpToBlock(blockDevice.blockSize)
            blockDevice.read(actual, 0, readLength)

            for (index in 0 until expectedLength) {
                if (expected[index] != actual[index]) {
                    return VerifyResult(false, checked + index, checked + index)
                }
            }

            checked += expectedLength
            cancellationToken.throwIfCancelled()
            onProgress(Progress(checked, imageSize, Progress.Phase.Verifying))
        }

        return VerifyResult(checked == imageSize, checked, null)
    }

    private fun readChunk(image: InputStream, buffer: ByteArray, maxLength: Int): Int {
        var total = 0
        while (total < maxLength) {
            val read = image.read(buffer, total, maxLength - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun Int.roundUpToBlock(blockSize: Int): Int =
        if (this % blockSize == 0) this else this + (blockSize - (this % blockSize))
}
