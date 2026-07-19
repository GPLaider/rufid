package io.github.rufid.ntfs

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.ExtractableIsoFile
import io.github.rufid.core.Progress
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * Cache-backed sparse NTFS image via packaged mkntfs + rufid_ntfs_stream.
 * Never Runtime.exec shell strings.
 *
 * Populate/verify stream ISO file bytes **directly** into process stdin
 * (no multi-GB protocol temp materialization).
 */
open class SparseNtfsImageBuilder(
    private val nativeLibraryDir: File,
    private val launcher: NtfsProcessLauncher = RealNtfsProcessLauncher(),
    private val sectorSize: Int = 512,
    private val partitionStartLba: Long = 2048L,
    private val heads: Int = 255,
    private val sectorsPerTrack: Int = 63,
    private val volumeLabel: String = "WININSTALL",
    private val toolResolver: (String) -> File = { name -> NtfsNativeTools.resolve(nativeLibraryDir, name) },
) {
    data class BuiltImage(
        val imageFile: File,
        val sizeBytes: Long,
        val sectorCount: Long,
    )

    /**
     * Create sparse image of [sizeBytes], format with mkntfs, populate from [files], verify.
     * Deletes incomplete image on any failure.
     *
     * Cache preflight uses sparse **allocated** payload estimate + NTFS metadata only
     * (not full logical partition size, not 2x ISO protocol temps).
     */
    open fun buildAndVerify(
        cacheDir: File,
        sizeBytes: Long,
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken = CancellationToken.None,
        requiredSourceBytes: Long = files.sumOf { it.size },
        onProgress: (Progress) -> Unit = {},
    ): BuiltImage {
        require(sizeBytes > 0 && sizeBytes % sectorSize == 0L) {
            "NTFS image size must be a positive multiple of $sectorSize."
        }
        val sectorCount = sizeBytes / sectorSize
        validateCacheSpace(cacheDir, requiredSourceBytes)
        cacheDir.mkdirs()
        val image = File(cacheDir, "rufid-ntfs-${System.nanoTime()}.img")
        try {
            cancellationToken.throwIfCancelled()
            createSparseFile(image, sizeBytes)
            runMkntfs(image, sectorCount, cancellationToken)
            streamPopulate(image, files, cancellationToken, onProgress)
            streamVerify(image, files, cancellationToken, onProgress)
            return BuiltImage(imageFile = image, sizeBytes = sizeBytes, sectorCount = sectorCount)
        } catch (error: Exception) {
            image.delete()
            throw error
        }
    }

    fun deleteImage(image: File) {
        if (image.exists() && !image.delete()) {
            image.deleteOnExit()
        }
    }

    /**
     * Sparse staging cache need:
     * - allocated NTFS data approximates payload + metadata overhead
     * - small slack for process/state
     * Does **not** charge full logical partition size or 2x ISO protocol dumps.
     */
    fun validateCacheSpace(cacheDir: File, payloadBytes: Long) {
        val store = cacheDir.resolve(".").absoluteFile
        val usable = store.usableSpace
        val ntfsMetadata = max(payloadBytes / 6, 32L * 1024L * 1024L)
        val sparseAllocated = payloadBytes + ntfsMetadata
        val slack = 64L * 1024L * 1024L
        val need = sparseAllocated + slack
        if (usable in 1 until need) {
            throw IOException(
                "Insufficient cache space under ${store.absolutePath}: usable=$usable need>=$need " +
                    "(payload=$payloadBytes ntfsMeta=$ntfsMetadata slack=$slack). " +
                    "No protocol temp materialization; logical partition holes not charged.",
            )
        }
    }

    private fun createSparseFile(file: File, sizeBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
    }

    private fun runMkntfs(image: File, sectorCount: Long, cancellationToken: CancellationToken) {
        val mkntfs = toolResolver(NtfsNativeTools.MKNTFS_SO)
        val command = listOf(
            mkntfs.absolutePath,
            "-Q",
            "-F",
            "-s", sectorSize.toString(),
            "-p", partitionStartLba.toString(),
            "-H", heads.toString(),
            "-S", sectorsPerTrack.toString(),
            "-L", volumeLabel,
            "-T",
            image.absolutePath,
            sectorCount.toString(),
        )
        val result = launcher.run(
            command = command,
            onCancelCheck = { cancellationToken.throwIfCancelled() },
        )
        result.requireSuccess("mkntfs")
    }

    private fun streamPopulate(
        image: File,
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
        onProgress: (Progress) -> Unit,
    ) {
        val total = files.sumOf { it.size }.coerceAtLeast(1L)
        onProgress(Progress(0, total, Progress.Phase.Populating))
        runStream(image, NtfsStreamProtocol.MODE_POPULATE, files, cancellationToken, "populate") { done ->
            onProgress(Progress(done.coerceAtMost(total), total, Progress.Phase.Populating))
        }
        onProgress(Progress(total, total, Progress.Phase.Populating))
    }

    private fun streamVerify(
        image: File,
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
        onProgress: (Progress) -> Unit,
    ) {
        val total = files.sumOf { it.size }.coerceAtLeast(1L)
        onProgress(Progress(0, total, Progress.Phase.VerifyingNtfs))
        runStream(image, NtfsStreamProtocol.MODE_VERIFY, files, cancellationToken, "verify") { done ->
            onProgress(Progress(done.coerceAtMost(total), total, Progress.Phase.VerifyingNtfs))
        }
        onProgress(Progress(total, total, Progress.Phase.VerifyingNtfs))
    }

    /**
     * Launch stream tool and pipe wire-protocol + ISO payload **live** into process stdin.
     * FORBIDDEN: materializing a protocolFile / rufid-ntfs-proto-*.bin (8GB ISO must not hit disk).
     */
    private fun runStream(
        image: File,
        mode: Int,
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
        label: String,
        onBytes: (Long) -> Unit,
    ) {
        val streamTool = toolResolver(NtfsNativeTools.STREAM_SO)
        val result = launcher.run(
            command = listOf(streamTool.absolutePath, image.absolutePath),
            // Direct streaming on the running process OutputStream (Process.getOutputStream).
            stdinWriter = { processStdin ->
                streamWireAndIsoToProcessStdin(
                    processStdin = processStdin,
                    mode = mode,
                    files = files,
                    cancellationToken = cancellationToken,
                    onBytes = onBytes,
                )
            },
            onCancelCheck = { cancellationToken.throwIfCancelled() },
        )
        result.requireSuccess("ntfs-stream $label")
    }

    /**
     * Write length-prefixed wire records and ISO file bytes straight into [processStdin].
     * Uses [ExtractableIsoFile.readAt] in chunks only; never a temp protocol dump.
     */
    private fun streamWireAndIsoToProcessStdin(
        processStdin: OutputStream,
        mode: Int,
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
        onBytes: (Long) -> Unit,
    ) {
        NtfsStreamProtocol.writeMode(processStdin, mode)
        val dirs = linkedSetOf<String>()
        files.forEach { file ->
            val path = validatedIsoPath(file.path)
            var prefix = ""
            path.split('/').dropLast(1).forEach { seg ->
                prefix = if (prefix.isEmpty()) seg else "$prefix/$seg"
                dirs += prefix
            }
        }
        dirs.sorted().forEach { dir ->
            cancellationToken.throwIfCancelled()
            NtfsStreamProtocol.writeDir(processStdin, dir)
        }
        val buffer = ByteArray(256 * 1024)
        var payloadDone = 0L
        files.forEach { file ->
            cancellationToken.throwIfCancelled()
            val path = validatedIsoPath(file.path)
            NtfsStreamProtocol.writeFileHeader(processStdin, path, file.size)
            var remaining = file.size
            var fileOffset = 0L
            while (remaining > 0) {
                cancellationToken.throwIfCancelled()
                val n = minOf(buffer.size.toLong(), remaining).toInt()
                val read = file.readAt(fileOffset, buffer, 0, n)
                if (read != n) {
                    throw IOException("Short ISO read for $path at $fileOffset (want $n got $read).")
                }
                processStdin.write(buffer, 0, n)
                fileOffset += n
                remaining -= n
                payloadDone += n
                onBytes(payloadDone)
            }
        }
        NtfsStreamProtocol.writeEnd(processStdin)
        processStdin.flush()
    }

    /** Validate the ISO path as provided; return the same string if safe. No normalize. */
    private fun validatedIsoPath(raw: String): String {
        NtfsStreamProtocol.requireSafeRelativePath(raw)
        return raw
    }
}

/** Conservative size estimate for NTFS image covering ISO payload. */
object NtfsImageSizing {
    fun requiredImageBytes(payloadBytes: Long, minBytes: Long = 64L * 1024L * 1024L): Long {
        val withOverhead = payloadBytes + max(payloadBytes / 6, 32L * 1024L * 1024L)
        val aligned = ((withOverhead + (1024L * 1024L) - 1) / (1024L * 1024L)) * (1024L * 1024L)
        return max(aligned, minBytes)
    }

    /** Sparse allocated estimate for cache preflight (not full logical partition). */
    fun estimatedSparseAllocatedBytes(payloadBytes: Long): Long {
        val ntfsMetadata = max(payloadBytes / 6, 32L * 1024L * 1024L)
        return payloadBytes + ntfsMetadata
    }
}
