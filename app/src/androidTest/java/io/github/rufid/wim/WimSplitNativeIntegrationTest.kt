package io.github.rufid.wim

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rufid.core.CacheBackedWimSplitStrategy
import io.github.rufid.core.CancellationToken
import io.github.rufid.core.ExtractableIsoFile
import io.github.rufid.core.NativeWimSplitEngine
import io.github.rufid.payload.PayloadCatalog
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator instrumentation for real JNI wimlib split.
 * Fixture: androidTest/assets/wim/split-fixture.wim (not in production APK).
 * Without ADB device: compile/assemble only; report instrumentation as NOT RUN.
 */
@RunWith(AndroidJUnit4::class)
class WimSplitNativeIntegrationTest {
    @Test
    fun nativeSplitProducesContinuousSwmPartsWithUniqueHashesAndCleansUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "WIM native libraries not packaged on device ABI",
            PayloadCatalog.wimSplitBridgePackaged(context),
        )

        val fixture = materializeFixture(instrumentation.context, context.cacheDir)
        val cacheRoot = File(context.cacheDir, "wim-split-androidTest").apply {
            deleteRecursively()
            mkdirs()
        }
        val strategy = CacheBackedWimSplitStrategy(
            cacheRoot = cacheRoot,
            engine = NativeWimSplitEngine,
            partSizeBytes = 2L * 1024L * 1024L,
        )
        var parts: List<ExtractableIsoFile> = emptyList()
        try {
            val raf = RandomAccessFile(fixture, "r")
            val source = ExtractableIsoFile(
                path = "sources/install.wim",
                size = fixture.length(),
                reader = { fileOffset, buffer, offset, length ->
                    raf.seek(fileOffset)
                    raf.read(buffer, offset, length)
                },
                onClose = { raf.close() },
            )
            try {
                parts = strategy.splitInstallWim(source, CancellationToken.None)
            } finally {
                source.close()
            }

            assertTrue("expected >=2 SWM parts, got ${parts.size}", parts.size >= 2)
            assertEquals("sources/install.swm", parts[0].path.lowercase())
            assertEquals("sources/install2.swm", parts[1].path.lowercase())
            for ((index, part) in parts.withIndex()) {
                val expected = if (index == 0) "sources/install.swm" else "sources/install${index + 1}.swm"
                assertEquals(expected, part.path.lowercase())
                assertTrue("${part.path} empty", part.size > 0L)
            }
            val digests = parts.map { sha256Of(it) }
            assertTrue(digests.all { it.isNotEmpty() })
            assertEquals(digests.size, digests.toSet().size)
            assertTrue(parts.none { it.path.contains("install.wim", ignoreCase = true) })
        } finally {
            parts.forEach { it.close() }
            strategy.cleanup()
            assertTrue(
                "cache not cleaned: ${cacheRoot.list()?.toList()}",
                cacheRoot.list().orEmpty().isEmpty() || !cacheRoot.exists(),
            )
            fixture.delete()
        }
    }

    private fun materializeFixture(assetContext: Context, cacheDir: File): File {
        val out = File(cacheDir, "split-fixture.wim")
        assetContext.assets.open("wim/split-fixture.wim").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("fixture missing or empty", out.isFile && out.length() > 0L)
        return out
    }

    private fun sha256Of(file: ExtractableIsoFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var offset = 0L
        while (offset < file.size) {
            val read = file.readAt(offset, buffer, 0, minOf(buffer.size.toLong(), file.size - offset).toInt())
            require(read > 0) {
                "early EOF while hashing ${file.path}: offset=$offset size=${file.size}"
            }
            digest.update(buffer, 0, read)
            offset += read
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
