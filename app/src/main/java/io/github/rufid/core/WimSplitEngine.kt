package io.github.rufid.core

import io.github.rufid.windows.WindowsIsoPlan
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

interface WimSplitEngine {
    fun split(
        wimFile: File,
        firstSwmFile: File,
        partSizeBytes: Long,
        cancellationToken: CancellationToken,
    )
}

object NativeWimSplitEngine : WimSplitEngine {
    override fun split(
        wimFile: File,
        firstSwmFile: File,
        partSizeBytes: Long,
        cancellationToken: CancellationToken,
    ) {
        NativeWimLib.split(wimFile, firstSwmFile, partSizeBytes, cancellationToken)
    }
}

/**
 * Pure validation for WIM split outputs (unit-tested without Android/native).
 * wimlib names the first part as the given path (`install.swm`) and subsequent
 * parts `install2.swm`, `install3.swm`, ...
 */
internal object WimSplitOutputValidation {
    private val SPLIT_PART_NAME = Regex("""^install(\d*)\.swm$""", RegexOption.IGNORE_CASE)

    fun requireValidPartSizeBytes(partSizeBytes: Long) {
        require(partSizeBytes > 0L) { "WIM split partSizeBytes must be positive: $partSizeBytes" }
        require(partSizeBytes <= WindowsIsoPlan.FAT32_MAX_FILE_SIZE) {
            "WIM split partSizeBytes exceeds FAT32 max file size: $partSizeBytes"
        }
    }

    fun splitPartNumber(name: String): Int {
        val match = SPLIT_PART_NAME.matchEntire(name)
            ?: throw IllegalArgumentException("Not a WIM split part name: $name")
        val digits = match.groupValues[1]
        return if (digits.isBlank()) 1 else digits.toInt()
    }

    fun isSplitPartName(name: String): Boolean = SPLIT_PART_NAME.matches(name)

    fun expectedPartFileName(partNumber: Int): String =
        if (partNumber == 1) "install.swm" else "install$partNumber.swm"

    /**
     * Validates SWM parts after native/host split, before any USB format/placement.
     * Rejects empty files, size over FAT32 limit, non-continuous numbering, and any install.wim.
     */
    fun validateSplitPartFiles(
        directoryFiles: List<File>,
        maxPartBytes: Long = WindowsIsoPlan.FAT32_MAX_FILE_SIZE,
    ): List<File> {
        // install.wim may remain on disk next to SWMs after split (materialized source);
        // it must never be selected as a placement part (.swm names only).
        val parts = directoryFiles
            .filter { it.isFile && isSplitPartName(it.name) }
            .sortedBy { splitPartNumber(it.name) }
        require(parts.isNotEmpty()) { "WIM split engine produced no .swm files." }
        require(parts.none { it.name.equals("install.wim", ignoreCase = true) }) {
            "install.wim must not be returned as a split part."
        }
        for ((index, part) in parts.withIndex()) {
            val expectedNumber = index + 1
            val actualNumber = splitPartNumber(part.name)
            val expectedName = expectedPartFileName(expectedNumber)
            require(actualNumber == expectedNumber && part.name.equals(expectedName, ignoreCase = true)) {
                "WIM split part number gap or name mismatch: expected $expectedName at index $expectedNumber, found ${part.name}"
            }
            require(part.length() > 0L) { "${part.name} is empty after WIM split." }
            require(part.length() <= maxPartBytes) {
                "${part.name} exceeds FAT32 file size limit after WIM split (${part.length()} > $maxPartBytes)."
            }
        }
        return parts
    }
}

class CacheBackedWimSplitStrategy(
    private val cacheRoot: File,
    private val engine: WimSplitEngine = NativeWimSplitEngine,
    private val partSizeBytes: Long = DEFAULT_PART_SIZE_BYTES,
) : WimSplitStrategy {
    private var splitDir: File? = null
    private val openStreams = mutableListOf<FileInputStream>()

    override fun splitInstallWim(
        source: ExtractableIsoFile,
        cancellationToken: CancellationToken,
    ): List<ExtractableIsoFile> {
        cleanup()
        WimSplitOutputValidation.requireValidPartSizeBytes(partSizeBytes)
        val directory = createSplitDirectory()
        splitDir = directory
        return try {
            val installWim = directory.resolve("install.wim")
            copySource(source, installWim, cancellationToken)

            val firstPart = directory.resolve("install.swm")
            cancellationToken.throwIfCancelled()
            engine.split(installWim, firstPart, partSizeBytes, cancellationToken)
            cancellationToken.throwIfCancelled()

            val listed = requireNotNull(directory.listFiles()) {
                "Unable to list WIM split output directory."
            }.toList()
            val parts = WimSplitOutputValidation.validateSplitPartFiles(listed)

            parts.map { part ->
                fileBackedExtractable("sources/${part.name.lowercase()}", part)
            }
        } catch (error: Throwable) {
            cleanup()
            throw error
        }
    }

    override fun cleanup() {
        openStreams.toList().forEach { stream ->
            runCatching { stream.close() }
        }
        openStreams.clear()
        splitDir?.deleteRecursively()
        splitDir = null
    }

    private fun createSplitDirectory(): File {
        cacheRoot.mkdirs()
        repeat(16) {
            val candidate = cacheRoot.resolve("rufid-wim-split-${System.nanoTime()}-$it")
            if (candidate.mkdir()) return candidate
        }
        throw IOException("Unable to create WIM split cache directory under $cacheRoot")
    }

    private fun copySource(source: ExtractableIsoFile, output: File, cancellationToken: CancellationToken) {
        try {
            output.outputStream().use { stream ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copied = 0L
                while (copied < source.size) {
                    cancellationToken.throwIfCancelled()
                    val read = source.readAt(
                        copied,
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), source.size - copied).toInt(),
                    )
                    if (read <= 0) throw IOException("install.wim source ended early at $copied")
                    stream.write(buffer, 0, read)
                    copied += read
                }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun fileBackedExtractable(path: String, file: File): ExtractableIsoFile {
        val input = FileInputStream(file)
        openStreams += input
        val channel = input.channel
        return ExtractableIsoFile(
            path = path,
            size = file.length(),
            reader = { fileOffset, buffer, outputOffset, length ->
                channel.read(ByteBuffer.wrap(buffer, outputOffset, length), fileOffset)
            },
            onClose = {
                runCatching { input.close() }
                openStreams.remove(input)
            },
        )
    }

    companion object {
        const val DEFAULT_PART_SIZE_BYTES: Long = 3800L * 1024L * 1024L
        private const val COPY_BUFFER_SIZE = 1024 * 1024
    }
}

internal object NativeWimLib {
    @Volatile
    private var loaded = false

    fun split(
        wimFile: File,
        firstSwmFile: File,
        partSizeBytes: Long,
        cancellationToken: CancellationToken,
    ) {
        WimSplitOutputValidation.requireValidPartSizeBytes(partSizeBytes)
        cancellationToken.throwIfCancelled()
        ensureLoaded()
        val error = splitWim(wimFile.absolutePath, firstSwmFile.absolutePath, partSizeBytes, cancellationToken)
        cancellationToken.throwIfCancelled()
        if (error != null) throw IOException("wimlib split failed: $error")
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("wimutils")
            System.loadLibrary("rufidwim")
            loaded = true
        } catch (error: UnsatisfiedLinkError) {
            throw UnsupportedOperationException(
                "WIM split engine requires packaged wimlib payload libraries.",
                error,
            )
        }
    }

    private external fun splitWim(
        inputWimPath: String,
        firstSwmPath: String,
        partSizeBytes: Long,
        cancellationToken: CancellationToken,
    ): String?
}
