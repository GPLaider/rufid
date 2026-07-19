package io.github.rufid.ntfs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rufid.core.ExtractableIsoFile
import io.github.rufid.payload.PayloadCatalog
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation on real installed APK nativeLibraryDir (not adb /data/local/tmp alone).
 * Builds 64 MiB NTFS image, populates nested/Unicode/>chunk file, verifies bytes, checks VBR.
 *
 * Packaged NTFS runtime is **asserted** (not assumeTrue-skipped): missing payload fails red.
 */
@RunWith(AndroidJUnit4::class)
class NtfsRuntimeNativeIntegrationTest {
    @Test
    fun sparseNtfsPopulateVerifyFromNativeLibraryDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "NTFS runtime tools must be packaged in the installed APK " +
                "(librufidmkntfs.so + librufidntfsstream.so)",
            PayloadCatalog.ntfsRuntimePackaged(context),
        )
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val extractDir = File(context.filesDir, "ntfs-tools").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val abis = android.os.Build.SUPPORTED_ABIS
        val mkntfs = NtfsNativeTools.resolveFromContext(
            nativeLibraryDir = nativeDir,
            apkPath = context.applicationInfo.sourceDir,
            extractDir = extractDir,
            name = NtfsNativeTools.MKNTFS_SO,
            preferredAbis = abis,
        )
        val stream = NtfsNativeTools.resolveFromContext(
            nativeLibraryDir = nativeDir,
            apkPath = context.applicationInfo.sourceDir,
            extractDir = extractDir,
            name = NtfsNativeTools.STREAM_SO,
            preferredAbis = abis,
        )
        assertTrue("mkntfs missing: $mkntfs", mkntfs.isFile && mkntfs.canExecute())
        assertTrue("stream missing: $stream", stream.isFile && stream.canExecute())

        val cache = File(context.cacheDir, "ntfs-instrument").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val builder = SparseNtfsImageBuilder(
            nativeLibraryDir = extractDir,
            toolResolver = { name ->
                NtfsNativeTools.resolveFromContext(
                    nativeLibraryDir = nativeDir,
                    apkPath = context.applicationInfo.sourceDir,
                    extractDir = extractDir,
                    name = name,
                    preferredAbis = abis,
                )
            },
        )
        val chunk = 300 * 1024
        val big = ByteArray(chunk) { i -> (i % 251).toByte() }
        // Unicode path via ASCII escapes only (no mojibake source literals).
        // \u8def\u5f84 = lu4; \u6587\u4ef6 = wen2jian4
        val unicodeRel =
            "nested/unicode-\u8def\u5f84/\u6587\u4ef6.bin"
        val files = listOf(
            memoryFile("nested/deep/dir/readme.txt", "hello-ntfs".toByteArray(Charsets.UTF_8)),
            memoryFile(unicodeRel, byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())),
            memoryFile("nested/deep/dir/big.dat", big),
        )
        val imageSize = 64L * 1024L * 1024L
        val built = builder.buildAndVerify(cache, imageSize, files)
        try {
            assertEquals(imageSize, built.sizeBytes)
            assertTrue(built.imageFile.isFile)
            val extents = SparseFileExtents.listAllocatedExtents(built.imageFile, imageSize)
            val allocatedBytes = SparseFileExtents.totalAllocatedBytes(extents)
            println(
                "NTFS_SPARSE_EXTENTS count=${extents.size} allocated=$allocatedBytes logical=$imageSize",
            )
            assertTrue("Sparse image must expose allocated extents", extents.isNotEmpty())
            assertTrue(
                "Sparse image allocated bytes must stay below logical size: allocated=$allocatedBytes logical=$imageSize",
                allocatedBytes in 1 until imageSize,
            )
            RandomAccessFile(built.imageFile, "r").use { raf ->
                val vbr = ByteArray(512)
                raf.readFully(vbr)
                val oem = String(vbr, 3, 4, Charsets.US_ASCII)
                assertEquals("NTFS", oem.trim())
                assertEquals(0x55, vbr[510].toInt() and 0xff)
                assertEquals(0xaa, vbr[511].toInt() and 0xff)
            }
        } finally {
            builder.deleteImage(built.imageFile)
        }
    }

    private fun memoryFile(path: String, data: ByteArray): ExtractableIsoFile =
        ExtractableIsoFile(
            path = path,
            size = data.size.toLong(),
            reader = { fileOffset, buffer, outputOffset, length ->
                val start = fileOffset.toInt()
                val n = minOf(length, data.size - start)
                if (n <= 0) return@ExtractableIsoFile -1
                data.copyInto(buffer, outputOffset, start, start + n)
                n
            },
        )
}
