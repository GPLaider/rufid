package io.github.rufid.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rufid.core.CancellationToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class SevenZipNativeIntegrationTest {
    @Test
    fun extractsInjectedRealSevenZipArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = context.filesDir.resolve("rufid-native-qa.7z")
        assertTrue("QA fixture was not injected.$archive", archive.isFile)
        val extracted = linkedMapOf<String, ByteArray>()

        SevenZipArchiveExtractor().extract(
            archiveFile = archive,
            sink = object : ArchiveEntrySink {
                override fun openEntry(entry: ArchiveEntryInfo): OutputStream = object : ByteArrayOutputStream() {
                    override fun close() {
                        extracted[entry.path] = toByteArray()
                        super.close()
                    }
                }
            },
            cancellationToken = CancellationToken.None,
            onEntry = {},
            onProgress = {},
        )

        assertArrayEquals("rufid-sevenzip-native-ok\n".toByteArray(), extracted.getValue("hello.txt"))
    }

    @Test
    fun rejectsInjectedTraversalArchiveBeforeOpeningSink() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = context.filesDir.resolve("rufid-unsafe-path.7z")
        assertTrue("QA fixture was not injected.", archive.isFile)
        var opened = false

        assertThrows(IllegalArgumentException::class.java) {
            SevenZipArchiveExtractor().extract(
                archiveFile = archive,
                sink = object : ArchiveEntrySink {
                    override fun openEntry(entry: ArchiveEntryInfo): OutputStream {
                        opened = true
                        return ByteArrayOutputStream()
                    }
                },
                cancellationToken = CancellationToken.None,
                onEntry = {},
                onProgress = {},
            )
        }

        assertFalse(opened)
    }
}
