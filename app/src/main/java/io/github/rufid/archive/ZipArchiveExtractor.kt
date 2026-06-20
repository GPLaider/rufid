package io.github.rufid.archive

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.Progress
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

data class ArchiveEntryInfo(
    val path: String,
    val size: Long,
    val directory: Boolean,
)

interface ArchiveEntrySink {
    fun createDirectory(entry: ArchiveEntryInfo) = Unit
    fun openEntry(entry: ArchiveEntryInfo): OutputStream?
}

class ZipArchiveExtractor(
    private val bufferSize: Int = 1024 * 1024,
) {
    fun extract(
        input: InputStream,
        sink: ArchiveEntrySink,
        cancellationToken: CancellationToken = CancellationToken.None,
        onEntry: (ArchiveEntryInfo) -> Unit,
        onProgress: (Progress) -> Unit,
    ) {
        val buffer = ByteArray(bufferSize)
        var copied = 0L

        ZipInputStream(input).use { zip ->
            while (true) {
                cancellationToken.throwIfCancelled()
                val entry = zip.nextEntry ?: break
                val info = ArchiveEntryInfo(
                    path = entry.name,
                    size = entry.size,
                    directory = entry.isDirectory,
                )
                onEntry(info)

                if (!info.directory) {
                    sink.openEntry(info)?.use { output ->
                        while (true) {
                            cancellationToken.throwIfCancelled()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            cancellationToken.throwIfCancelled()
                            onProgress(Progress(copied, 0, Progress.Phase.Writing))
                        }
                    }
                } else {
                    sink.createDirectory(info)
                }
                zip.closeEntry()
            }
        }

        onProgress(Progress(copied, copied, Progress.Phase.Finished))
    }
}
