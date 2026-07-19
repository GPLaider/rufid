package io.github.rufid

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rufid.core.CacheBackedWimSplitStrategy
import io.github.rufid.core.CancellationToken
import io.github.rufid.core.OperationCancelledException
import io.github.rufid.core.Progress
import io.github.rufid.core.SeekableBlockDevice
import io.github.rufid.ntfs.NtfsNativeTools
import io.github.rufid.ntfs.RealNtfsProcessLauncher
import io.github.rufid.ntfs.SparseNtfsImageBuilder
import io.github.rufid.ntfs.WindowsInstallBackendMode
import io.github.rufid.ntfs.WindowsIsoBackendWriter
import io.github.rufid.storage.AndroidSeekableByteSource
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HIL harness for creating pullable virtual disks through the production Windows writer.
 * The target is always a regular file; this test never opens Android USB devices.
 */
@RunWith(AndroidJUnit4::class)
class WindowsBackendImageInstrumentationTest {
    @Test
    fun runProductionWindowsBackend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val modeName = requireArgument(arguments.getString("mode"), "mode")
        val mode = parseMode(modeName)
        val sourceFile = File(requireArgument(arguments.getString("iso"), "iso"))
        require(sourceFile.isFile) { "ISO does not exist: ${sourceFile.absolutePath}" }
        val outputDir = File(
            arguments.getString("outputDir")
                ?: requireNotNull(context.getExternalFilesDir(null)).resolve("virtual-boot").absolutePath,
        ).apply { mkdirs() }
        require(outputDir.isDirectory) { "Unable to create output directory: ${outputDir.absolutePath}" }
        val sizeBytes = requireArgument(arguments.getString("sizeBytes"), "sizeBytes").toLong()
        require(sizeBytes > 0L && sizeBytes % BLOCK_SIZE == 0L) {
            "sizeBytes must be a positive multiple of $BLOCK_SIZE: $sizeBytes"
        }
        val expectedCancel = arguments.getString("expectCancel").toBoolean()
        val expectedFailure = arguments.getString("expectFailure").toBoolean()
        require(!(expectedCancel && expectedFailure)) { "expectCancel and expectFailure are mutually exclusive." }

        val outputFile = outputDir.resolve("rufid-$modeName.img")
        val manifestFile = outputDir.resolve("rufid-$modeName.manifest.json")
        outputFile.delete()
        manifestFile.delete()
        val startedAtMs = System.currentTimeMillis()
        val sourceHash = sha256(sourceFile)
        val expectedSourceHash = arguments.getString("expectedIsoSha256")?.lowercase()
        if (expectedSourceHash != null) {
            require(sourceHash == expectedSourceHash) {
                "ISO SHA-256 mismatch: expected=$expectedSourceHash actual=$sourceHash"
            }
        }

        val progressEvents = JSONArray()
        val manifest = JSONObject()
            .put("schema", 1)
            .put("backend", WindowsIsoBackendWriter::class.java.name)
            .put("mode", mode.name)
            .put("sourcePath", sourceFile.absolutePath)
            .put("sourceSize", sourceFile.length())
            .put("sourceSha256", sourceHash)
            .put("outputPath", outputFile.absolutePath)
            .put("requestedOutputSize", sizeBytes)
            .put("startedAtMs", startedAtMs)
            .put("status", "running")
            .put("physicalUsbOpened", false)
            .put("progress", progressEvents)
        writeManifest(manifestFile, manifest)
        emit("RUFID_HIL_PROVENANCE ${manifest.toString()}")

        val cancellationToken = CancellationToken.active()
        if (expectedCancel) cancellationToken.cancel()
        var lastPhase: Progress.Phase? = null
        var lastBucket = -1
        try {
            FileBlockDevice(outputFile, sizeBytes).use { blockDevice ->
                AndroidSeekableByteSource(
                    ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY),
                    sourceFile.length(),
                ).use { source ->
                    val usesNtfs = mode.usesNtfs
                    val extractDir = context.filesDir.resolve("ntfs-tools").apply { mkdirs() }
                    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
                    val imageBuilder = if (usesNtfs) {
                        SparseNtfsImageBuilder(
                            nativeLibraryDir = extractDir,
                            launcher = RealNtfsProcessLauncher.forAndroid(context.cacheDir),
                            toolResolver = { name ->
                                NtfsNativeTools.resolveFromContext(
                                    nativeLibraryDir = nativeDir,
                                    apkPath = context.applicationInfo.sourceDir,
                                    extractDir = extractDir,
                                    name = name,
                                    preferredAbis = Build.SUPPORTED_ABIS,
                                )
                            },
                        )
                    } else {
                        null
                    }
                    val helperImage = if (usesNtfs) {
                        context.assets.open(UEFI_NTFS_IMAGE_ASSET).use { it.readBytes() }
                    } else {
                        null
                    }

                    WindowsIsoBackendWriter(
                        blockDevice = blockDevice,
                        mode = mode,
                        imageBuilder = imageBuilder,
                        helperImage = helperImage,
                        cacheDir = context.cacheDir,
                        wimSplitStrategy = CacheBackedWimSplitStrategy(context.cacheDir),
                    ).write(
                        source = source,
                        imageName = sourceFile.name,
                        cancellationToken = cancellationToken,
                    ) { progress ->
                        val bucket = progress.percent / PROGRESS_BUCKET_PERCENT
                        if (progress.phase != lastPhase || bucket != lastBucket || progress.phase == Progress.Phase.Finished) {
                            lastPhase = progress.phase
                            lastBucket = bucket
                            val event = JSONObject()
                                .put("phase", progress.phase.name)
                                .put("percent", progress.percent)
                                .put("bytesDone", progress.bytesDone)
                                .put("bytesTotal", progress.bytesTotal)
                            progressEvents.put(event)
                            writeManifest(manifestFile, manifest)
                            emit("RUFID_HIL_PROGRESS mode=$modeName ${event}")
                        }
                    }
                }
            }

            if (expectedCancel) fail("Production backend ignored a pre-cancelled token.")
            if (expectedFailure) fail("Production backend unexpectedly succeeded in expected-failure mode.")
            require(lastPhase == Progress.Phase.Finished) {
                "Production backend returned without Finished progress; lastPhase=$lastPhase"
            }
            val outputHash = sha256(outputFile)
            manifest
                .put("status", "success")
                .put("finishedAtMs", System.currentTimeMillis())
                .put("outputSize", outputFile.length())
                .put("outputSha256", outputHash)
                .put("lastPhase", lastPhase?.name)
            writeManifest(manifestFile, manifest)
            emit("RUFID_HIL_PROVENANCE ${manifest.toString()}")
        } catch (cancelled: OperationCancelledException) {
            manifest
                .put("status", "cancelled")
                .put("finishedAtMs", System.currentTimeMillis())
                .put("error", cancelled.toString())
            writeManifest(manifestFile, manifest)
            emit("RUFID_HIL_PROVENANCE ${manifest.toString()}")
            outputFile.delete()
            if (!expectedCancel) throw cancelled
            assertFalse("Cancelled run must not be marked successful", manifest.getString("status") == "success")
        } catch (error: Throwable) {
            manifest
                .put("status", "failed")
                .put("finishedAtMs", System.currentTimeMillis())
                .put("error", error.toString())
            writeManifest(manifestFile, manifest)
            emit("RUFID_HIL_PROVENANCE ${manifest.toString()}")
            outputFile.delete()
            if (!expectedFailure) throw error
            assertFalse("Failed run must not be marked successful", manifest.getString("status") == "success")
        }
    }

    private fun parseMode(value: String): WindowsInstallBackendMode = when (value) {
        "fat32" -> WindowsInstallBackendMode.Fat32Extraction
        "ntfs-mbr" -> WindowsInstallBackendMode.NtfsUefiMbr
        "ntfs-gpt" -> WindowsInstallBackendMode.NtfsUefiGpt
        else -> throw IllegalArgumentException("Unsupported mode: $value")
    }

    private fun requireArgument(value: String?, name: String): String =
        requireNotNull(value?.takeIf { it.isNotBlank() }) { "Missing instrumentation argument: $name" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun writeManifest(file: File, manifest: JSONObject) {
        val temporary = file.resolveSibling("${file.name}.tmp")
        temporary.writeText(manifest.toString(2))
        check(temporary.renameTo(file)) { "Unable to atomically replace ${file.absolutePath}" }
    }

    private fun emit(message: String) {
        Log.i(LOG_TAG, message)
        println(message)
    }

    private class FileBlockDevice(file: File, override val sizeBytes: Long) : SeekableBlockDevice, AutoCloseable {
        override val blockSize: Int = BLOCK_SIZE
        private val randomAccess = RandomAccessFile(file, "rw").also { it.setLength(sizeBytes) }

        override fun seek(byteOffset: Long) = randomAccess.seek(byteOffset)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = randomAccess.read(buffer, offset, length)

        override fun write(buffer: ByteArray, offset: Int, length: Int) = randomAccess.write(buffer, offset, length)

        override fun flush() = randomAccess.fd.sync()

        override fun close() = randomAccess.close()
    }

    private companion object {
        const val BLOCK_SIZE = 512
        const val HASH_BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_BUCKET_PERCENT = 5
        const val LOG_TAG = "RufidHIL"
        const val UEFI_NTFS_IMAGE_ASSET = "payloads/uefi/uefi-ntfs.img"
    }
}
