package io.github.rufid.payload

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCatalogTest {
    @Test
    fun findsNativeLibraryInsideApkZip() {
        val apk = apkWithEntries(
            "lib/arm64-v8a/libwimutils.so",
            "lib/arm64-v8a/librufidwim.so",
        )

        try {
            assertTrue(PayloadCatalog.apkContainsNativeLibrary(apk.absolutePath, "libwimutils.so", TEST_ABIS))
            assertTrue(PayloadCatalog.apkContainsNativeLibrary(apk.absolutePath, "librufidwim.so", TEST_ABIS))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun doesNotMatchMissingNativeLibrary() {
        val apk = apkWithEntries("assets/payloads/uefi/uefi-ntfs.img")

        try {
            assertFalse(PayloadCatalog.apkContainsNativeLibrary(apk.absolutePath, "libwimutils.so", TEST_ABIS))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun requiresBothNativeLibrariesForWimSplitBridge() {
        val incompleteApk = apkWithEntries("lib/arm64-v8a/libwimutils.so")
        val completeApk = apkWithEntries(
            "lib/arm64-v8a/libwimutils.so",
            "lib/arm64-v8a/librufidwim.so",
        )

        try {
            assertFalse(
                PayloadCatalog.apkContainsAllNativeLibraries(
                    incompleteApk.absolutePath,
                    listOf("libwimutils.so", "librufidwim.so"),
                    TEST_ABIS,
                ),
            )
            assertTrue(
                PayloadCatalog.apkContainsAllNativeLibraries(
                    completeApk.absolutePath,
                    listOf("libwimutils.so", "librufidwim.so"),
                    TEST_ABIS,
                ),
            )
        } finally {
            incompleteApk.delete()
            completeApk.delete()
        }
    }

    @Test
    fun rejectsWimSplitLibrariesFromDifferentAbis() {
        val apk = apkWithEntries(
            "lib/arm64-v8a/libwimutils.so",
            "lib/x86_64/librufidwim.so",
        )

        try {
            assertFalse(
                PayloadCatalog.apkContainsAllNativeLibraries(
                    apk.absolutePath,
                    listOf("libwimutils.so", "librufidwim.so"),
                    listOf("arm64-v8a", "x86_64"),
                ),
            )
        } finally {
            apk.delete()
        }
    }

    private fun apkWithEntries(vararg entries: String): File {
        val apk = File.createTempFile("rufid-payload-status", ".apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            entries.forEach { entryName ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        return apk
    }

    private companion object {
        val TEST_ABIS = listOf("arm64-v8a", "x86_64")
    }
}
