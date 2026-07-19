package io.github.rufid.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveExtractorTest {
    @Test
    fun acceptsStandardDirectoryEntryWithTrailingSlash() {
        val directories = mutableListOf<String>()

        ZipArchiveExtractor().extract(
            input = zipWithEntry("docs/", directory = true),
            sink = object : ArchiveEntrySink {
                override fun createDirectory(entry: ArchiveEntryInfo) {
                    directories += entry.path
                }

                override fun openEntry(entry: ArchiveEntryInfo): OutputStream = ByteArrayOutputStream()
            },
            onEntry = {},
            onProgress = {},
        )

        assertEquals(listOf("docs/"), directories)
    }

    @Test
    fun rejectsFileEntryWithTrailingSlashBeforeSinkWrite() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathValidator.requireSafe("file/", directory = false)
        }
    }

    private fun zipWithEntry(path: String, directory: Boolean): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entry = ZipEntry(path)
            if (directory) entry.size = 0
            zip.putNextEntry(entry)
            zip.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
