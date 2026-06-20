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
        val apk = apkWithEntries("lib/arm64-v8a/libwimutils.so")

        try {
            assertTrue(PayloadCatalog.apkContainsNativeLibrary(apk.absolutePath, "libwimutils.so"))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun doesNotMatchMissingNativeLibrary() {
        val apk = apkWithEntries("assets/payloads/uefi/uefi-ntfs.img")

        try {
            assertFalse(PayloadCatalog.apkContainsNativeLibrary(apk.absolutePath, "libwimutils.so"))
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
}
