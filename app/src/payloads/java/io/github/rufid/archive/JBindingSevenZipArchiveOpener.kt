package io.github.rufid.archive

import io.github.rufid.core.CancellationToken
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

class JBindingSevenZipArchiveOpener : SevenZipArchiveOpener {
    override fun open(file: File): SevenZipArchive = JBindingSevenZipArchive(file)
}

private class JBindingSevenZipArchive(file: File) : SevenZipArchive {
    private val input = RandomAccessFileInStream(RandomAccessFile(file, "r"))
    private val archive: IInArchive = try {
        SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, input)
    } catch (error: Throwable) {
        input.close()
        throw error
    }

    override val entries: List<SevenZipEntry> = (0 until archive.numberOfItems).map { index ->
        SevenZipEntry(
            path = requireNotNull(archive.getStringProperty(index, PropID.PATH)) {
                "7-Zip entry $index has no path."
            },
            size = (archive.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: 0L,
            directory = archive.getProperty(index, PropID.IS_FOLDER) == true,
        )
    }

    override fun extract(index: Int, output: OutputStream, cancellationToken: CancellationToken) {
        val result = archive.extractSlow(
            index,
            ISequentialOutStream { bytes ->
                cancellationToken.throwIfCancelled()
                output.write(bytes)
                bytes.size
            },
        )
        check(result == ExtractOperationResult.OK) { "7-Zip extraction failed for entry $index: $result" }
    }

    override fun close() {
        try {
            archive.close()
        } finally {
            input.close()
        }
    }
}
