package io.github.rufid.archive

import io.github.rufid.core.CancellationToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

class SevenZipArchiveExtractorTest {
    @Test
    fun extractsFilesAndDirectoriesThroughArchiveSink() {
        val archive = FakeSevenZipArchive(
            listOf(
                SevenZipEntry("docs", 0, directory = true) to byteArrayOf(),
                SevenZipEntry("docs/readme.txt", 5, directory = false) to "hello".toByteArray(),
            ),
        )
        val sink = RecordingSink()

        SevenZipArchiveExtractor(opener = { archive }).extract(
            archiveFile = File("fixture.7z"),
            sink = sink,
            cancellationToken = CancellationToken.None,
            onEntry = {},
            onProgress = {},
        )

        assertEquals(listOf("docs"), sink.directories)
        assertArrayEquals("hello".toByteArray(), sink.files.getValue("docs/readme.txt"))
        assertEquals(true, archive.closed)
    }

    @Test
    fun rejectsUnsafePathsBeforeOpeningSinkEntry() {
        val unsafePaths = listOf(
            "../escape.txt",
            "/absolute.txt",
            "C:/drive.txt",
            "safe/../../escape.txt",
            "safe\\escape.txt",
            "control\u0000name.txt",
            "control\nname.txt",
        )

        for (path in unsafePaths) {
            val archive = FakeSevenZipArchive(
                listOf(SevenZipEntry(path, 1, directory = false) to byteArrayOf(1)),
            )
            val sink = RecordingSink()

            assertThrows(IllegalArgumentException::class.java) {
                SevenZipArchiveExtractor(opener = { archive }).extract(
                    archiveFile = File("unsafe.7z"),
                    sink = sink,
                    cancellationToken = CancellationToken.None,
                    onEntry = {},
                    onProgress = {},
                )
            }

            assertEquals(emptyMap<String, ByteArray>(), sink.files)
            assertEquals(emptyList<String>(), sink.directories)
        }
    }

    @Test
    fun propagatesSinkOpenFailureInsteadOfReportingSuccess() {
        val archive = FakeSevenZipArchive(
            listOf(SevenZipEntry("file.txt", 1, directory = false) to byteArrayOf(1)),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            SevenZipArchiveExtractor(opener = { archive }).extract(
                archiveFile = File("fixture.7z"),
                sink = object : ArchiveEntrySink {
                    override fun openEntry(entry: ArchiveEntryInfo): OutputStream = error("sink open failed")
                },
                cancellationToken = CancellationToken.None,
                onEntry = {},
                onProgress = {},
            )
        }

        assertEquals("sink open failed", error.message)
    }

    private class FakeSevenZipArchive(
        private val contents: List<Pair<SevenZipEntry, ByteArray>>,
    ) : SevenZipArchive {
        override val entries: List<SevenZipEntry> = contents.map { it.first }
        var closed = false

        override fun extract(index: Int, output: OutputStream, cancellationToken: CancellationToken) {
            cancellationToken.throwIfCancelled()
            output.write(contents[index].second)
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingSink : ArchiveEntrySink {
        val directories = mutableListOf<String>()
        val files = linkedMapOf<String, ByteArray>()

        override fun createDirectory(entry: ArchiveEntryInfo) {
            directories += entry.path
        }

        override fun openEntry(entry: ArchiveEntryInfo): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                files[entry.path] = toByteArray()
                super.close()
            }
        }
    }
}
