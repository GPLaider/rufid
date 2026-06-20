package io.github.rufid.download

import io.github.rufid.core.Progress
import io.github.rufid.core.RawImageWriter
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.core.CancellationToken
import java.io.IOException
import java.net.URL

class DirectDownloadWriter(
    private val blockDevice: SeekableBlockDevice,
) {
    fun write(
        url: String,
        cancellationToken: CancellationToken = CancellationToken.None,
        onProgress: (Progress) -> Unit,
    ): Long {
        val connection = URL(url).openConnection()
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
        if (contentLength > blockDevice.sizeBytes) {
            throw IOException("Download is larger than target device: download=$contentLength target=${blockDevice.sizeBytes}")
        }

        connection.getInputStream().use { input ->
            RawImageWriter(blockDevice).write(input, contentLength, cancellationToken, onProgress)
        }
        return contentLength
    }
}
