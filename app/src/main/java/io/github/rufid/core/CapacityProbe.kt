package io.github.rufid.core

import java.util.zip.CRC32

data class CapacityProbeSample(
    val byteOffset: Long,
    val crc32: Long,
)

data class CapacityProbeResult(
    val samples: List<CapacityProbeSample>,
    val blockSize: Int,
) {
    val checkedBytes: Long
        get() = samples.size.toLong() * blockSize.toLong()
}

class CapacityProbe(
    private val blockDevice: SeekableBlockDevice,
) {
    fun run(
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ): CapacityProbeResult {
        val offsets = sampleOffsets()
        val buffer = ByteArray(blockDevice.blockSize)
        val samples = mutableListOf<CapacityProbeSample>()

        offsets.forEachIndexed { index, offset ->
            cancellationToken.throwIfCancelled()
            blockDevice.seek(offset)
            blockDevice.read(buffer, 0, buffer.size)
            samples += CapacityProbeSample(offset, buffer.crc32())
            cancellationToken.throwIfCancelled()
            onProgress(Progress(index + 1L, offsets.size.toLong(), Progress.Phase.Verifying))
        }

        return CapacityProbeResult(samples, blockDevice.blockSize)
    }

    private fun sampleOffsets(): List<Long> {
        val blockSize = blockDevice.blockSize.toLong()
        val lastOffset = (blockDevice.sizeBytes - blockSize).coerceAtLeast(0L)
        val middleOffset = ((blockDevice.sizeBytes / 2L) / blockSize) * blockSize
        return listOf(0L, middleOffset, lastOffset).distinct()
    }

    private fun ByteArray.crc32(): Long =
        CRC32().also { it.update(this) }.value
}
