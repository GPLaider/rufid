package io.github.rufid.core

import kotlin.math.max

data class BenchmarkResult(
    val bytesRead: Long,
    val elapsedMillis: Long,
) {
    val bytesPerSecond: Long
        get() = if (elapsedMillis <= 0L) 0L else (bytesRead * 1000L) / elapsedMillis
}

class ReadBenchmark(
    private val blockDevice: SeekableBlockDevice,
    bufferSize: Int = 1024 * 1024,
) {
    private val alignedBufferSize = bufferSize - (bufferSize % blockDevice.blockSize)

    fun run(
        sampleBytes: Long = minOf(blockDevice.sizeBytes, 256L * 1024L * 1024L),
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ): BenchmarkResult {
        require(alignedBufferSize > 0)

        val buffer = ByteArray(alignedBufferSize)
        var readTotal = 0L
        val started = System.currentTimeMillis()

        blockDevice.seek(0)
        while (readTotal < sampleBytes) {
            cancellationToken.throwIfCancelled()
            val length = minOf(buffer.size.toLong(), sampleBytes - readTotal).toInt()
                .roundUpToBlock(blockDevice.blockSize)
            blockDevice.read(buffer, 0, length)
            readTotal += length
            cancellationToken.throwIfCancelled()
            onProgress(Progress(minOf(readTotal, sampleBytes), sampleBytes, Progress.Phase.Verifying))
        }

        return BenchmarkResult(readTotal, max(1L, System.currentTimeMillis() - started))
    }

    private fun Int.roundUpToBlock(blockSize: Int): Int =
        if (this % blockSize == 0) this else this + (blockSize - (this % blockSize))
}
