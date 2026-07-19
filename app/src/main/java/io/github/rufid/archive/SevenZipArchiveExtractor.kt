package io.github.rufid.archive

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.Progress
import java.io.Closeable
import java.io.File
import java.io.OutputStream

data class SevenZipEntry(
    val path: String,
    val size: Long,
    val directory: Boolean,
)

interface SevenZipArchive : Closeable {
    val entries: List<SevenZipEntry>
    fun extract(index: Int, output: OutputStream, cancellationToken: CancellationToken)
}

fun interface SevenZipArchiveOpener {
    fun open(file: File): SevenZipArchive
}

class SevenZipArchiveExtractor(
    private val opener: SevenZipArchiveOpener = OptionalSevenZipArchiveOpener,
) {
    fun extract(
        archiveFile: File,
        sink: ArchiveEntrySink,
        cancellationToken: CancellationToken = CancellationToken.None,
        onEntry: (ArchiveEntryInfo) -> Unit,
        onProgress: (Progress) -> Unit,
    ) {
        opener.open(archiveFile).use { archive ->
            archive.entries.forEach { ArchivePathValidator.requireSafe(it.path, it.directory) }
            val total = archive.entries.filterNot { it.directory }.sumOf { maxOf(0, it.size) }
            var copied = 0L

            archive.entries.forEachIndexed { index, entry ->
                cancellationToken.throwIfCancelled()
                val info = ArchiveEntryInfo(entry.path, entry.size, entry.directory)
                onEntry(info)
                if (entry.directory) {
                    sink.createDirectory(info)
                } else {
                    sink.openEntry(info).use { destination ->
                        val progressOutput = object : OutputStream() {
                            override fun write(value: Int) {
                                cancellationToken.throwIfCancelled()
                                destination.write(value)
                                copied++
                                onProgress(Progress(copied, total, Progress.Phase.Writing))
                            }

                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                cancellationToken.throwIfCancelled()
                                destination.write(bytes, offset, length)
                                copied += length
                                onProgress(Progress(copied, total, Progress.Phase.Writing))
                            }
                        }
                        archive.extract(index, progressOutput, cancellationToken)
                    }
                }
            }
            onProgress(Progress(copied, copied, Progress.Phase.Finished))
        }
    }
}

private object OptionalSevenZipArchiveOpener : SevenZipArchiveOpener {
    override fun open(file: File): SevenZipArchive {
        val implementation = runCatching {
            Class.forName(JBINDING_OPENER_CLASS)
                .getDeclaredConstructor()
                .newInstance() as SevenZipArchiveOpener
        }.getOrElse { error ->
            throw UnsupportedOperationException("7-Zip runtime is not packaged in this APK.", error)
        }
        return implementation.open(file)
    }

    private const val JBINDING_OPENER_CLASS = "io.github.rufid.archive.JBindingSevenZipArchiveOpener"
}
