package io.github.rufid.ntfs

import io.github.rufid.core.OperationCancelledException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Result of a native helper process. */
data class NtfsProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0

    fun requireSuccess(label: String) {
        if (!succeeded) {
            throw IOException(
                "$label failed (exit=$exitCode). stderr=${stderr.take(4000)} stdout=${stdout.take(1000)}",
            )
        }
    }
}

/**
 * Launches ProcessBuilder with argv list only (never shell strings).
 *
 * Stdin is produced by [stdinWriter] **after** the process starts, concurrent with
 * bounded stdout/stderr drain threads. Never materializes multi-GB protocol temps.
 */
interface NtfsProcessLauncher {
    fun run(
        command: List<String>,
        workingDir: File? = null,
        /**
         * Optional producer that writes the process stdin stream.
         * Invoked after [Process] start; must not hold unbounded memory of payload.
         */
        stdinWriter: ((OutputStream) -> Unit)? = null,
        onCancelCheck: (() -> Unit)? = null,
    ): NtfsProcessResult
}

class RealNtfsProcessLauncher(
    /** Max captured chars per stream (bounded; excess dropped with marker). */
    private val maxCaptureChars: Int = 64 * 1024,
    /**
     * Host/Android scratch directory. **Not** used for ISO/protocol stdin (streamed live).
     * Prefer [forAndroid] with `context.cacheDir`. Host default is a non-null tmp path.
     */
    private val hostCacheDir: File = defaultHostCacheDir(),
) : NtfsProcessLauncher {
    init {
        // Keep reference so callers can inject Android cacheDir; no temp-file stdin design.
        require(hostCacheDir.path.isNotEmpty()) { "hostCacheDir path must be non-empty." }
    }

    override fun run(
        command: List<String>,
        workingDir: File?,
        stdinWriter: ((OutputStream) -> Unit)?,
        onCancelCheck: (() -> Unit)?,
    ): NtfsProcessResult {
        require(command.isNotEmpty()) { "Empty command" }
        val builder = ProcessBuilder(command)
        if (workingDir != null) {
            builder.directory(workingDir)
        }
        // Point process TMP* at injected host/Android cache (not used for our ISO stdin stream).
        val cacheAbs = hostCacheDir.absolutePath
        builder.environment()["TMPDIR"] = cacheAbs
        builder.environment()["TMP"] = cacheAbs
        builder.environment()["TEMP"] = cacheAbs
        builder.redirectErrorStream(false)
        val process = builder.start()

        val stdoutRef = AtomicReference("")
        val stderrRef = AtomicReference("")
        val drainError = AtomicReference<Throwable?>(null)

        val outThread = Thread(
            {
                try {
                    stdoutRef.set(boundedRead(process.inputStream, maxCaptureChars))
                } catch (t: Throwable) {
                    drainError.compareAndSet(null, t)
                }
            },
            "rufid-ntfs-stdout-drain",
        )
        val errThread = Thread(
            {
                try {
                    stderrRef.set(boundedRead(process.errorStream, maxCaptureChars))
                } catch (t: Throwable) {
                    drainError.compareAndSet(null, t)
                }
            },
            "rufid-ntfs-stderr-drain",
        )
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val producerError = AtomicReference<Throwable?>(null)
        try {
            if (stdinWriter != null) {
                try {
                    process.outputStream.use { stdin ->
                        stdinWriter.invoke(object : OutputStream() {
                            override fun write(b: Int) {
                                onCancelCheck?.invoke()
                                stdin.write(b)
                            }

                            override fun write(b: ByteArray, off: Int, len: Int) {
                                onCancelCheck?.invoke()
                                stdin.write(b, off, len)
                            }

                            override fun flush() {
                                stdin.flush()
                            }

                            override fun close() {
                                // Process outputStream closed by outer use{}
                            }
                        })
                        stdin.flush()
                    }
                } catch (t: Throwable) {
                    producerError.set(t)
                    process.destroyForcibly()
                }
            } else {
                process.outputStream.close()
            }

            // Wait with cancel polling
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                val prod = producerError.get()
                if (prod != null) break
                try {
                    onCancelCheck?.invoke()
                } catch (cancel: Exception) {
                    process.destroyForcibly()
                    joinDrain(outThread, errThread)
                    val result = snapshotResult(process, stdoutRef, stderrRef)
                    if (cancel is OperationCancelledException) {
                        result.asExceptionCause()?.let { cancel.addSuppressed(it) }
                        throw cancel
                    }
                    val wrapped = IOException(
                        "Native tool cancelled. stderr=${result.stderr.take(2000)}",
                        cancel,
                    )
                    result.asExceptionCause()?.let { wrapped.addSuppressed(it) }
                    throw wrapped
                }
            }

            // Ensure terminated if producer failed mid-stream
            if (producerError.get() != null && process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }

            joinDrain(outThread, errThread)
            val exit = try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                process.exitValue()
            }
            val result = NtfsProcessResult(exit, stdoutRef.get(), stderrRef.get())

            val prod = producerError.get()
            if (prod != null) {
                val wrapped = when (prod) {
                    is Exception -> prod
                    else -> IOException("stdin producer failed", prod)
                }
                if (wrapped is IOException) {
                    wrapped.addSuppressed(
                        IOException(
                            "process exit=$exit stderr=${result.stderr.take(2000)} stdout=${result.stdout.take(500)}",
                        ),
                    )
                }
                drainError.get()?.let { wrapped.addSuppressed(it) }
                throw wrapped
            }
            drainError.get()?.let { drain ->
                throw IOException(
                    "Native tool drain failed (exit=$exit). stderr=${result.stderr.take(2000)}",
                    drain,
                )
            }
            return result
        } catch (t: Throwable) {
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            }
            joinDrain(outThread, errThread)
            // Preserve original cause chain
            when (t) {
                is Exception -> throw t
                else -> throw IOException("Native tool failed", t)
            }
        }
    }

    private fun joinDrain(outThread: Thread, errThread: Thread) {
        outThread.join(30_000)
        errThread.join(30_000)
    }

    private fun snapshotResult(
        process: Process,
        stdoutRef: AtomicReference<String>,
        stderrRef: AtomicReference<String>,
    ): NtfsProcessResult {
        val exit = if (process.isAlive) {
            -1
        } else {
            runCatching { process.exitValue() }.getOrDefault(-1)
        }
        return NtfsProcessResult(exit, stdoutRef.get(), stderrRef.get())
    }

    private fun NtfsProcessResult.asExceptionCause(): Exception? =
        if (exitCode != 0 || stderr.isNotEmpty() || stdout.isNotEmpty()) {
            IOException("exit=$exitCode stderr=${stderr.take(2000)} stdout=${stdout.take(500)}")
        } else {
            null
        }

    /** Bounded text capture from process streams (avoids multi-GB memory). */
    private fun boundedRead(stream: InputStream, maxChars: Int): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val room = maxChars - total
            if (room <= 0) {
                // Drain remainder without storing
                while (stream.read(buf) >= 0) {
                    // discard
                }
                val text = bos.toString(Charsets.UTF_8.name())
                return text + "\n[truncated ${maxChars} chars]"
            }
            val take = minOf(n, room)
            bos.write(buf, 0, take)
            total += take
            if (take < n) {
                while (stream.read(buf) >= 0) {
                    // discard rest
                }
                return bos.toString(Charsets.UTF_8.name()) + "\n[truncated ${maxChars} chars]"
            }
        }
        return bos.toString(Charsets.UTF_8.name())
    }

    companion object {
        /**
         * Explicit non-null host temp root (fixes String? from getProperty alone).
         * Order: java.io.tmpdir, TMP, TEMP, /tmp.
         */
        fun defaultHostCacheDir(): File {
            val tmp: String =
                System.getProperty("java.io.tmpdir")
                    ?: System.getenv("TMP")
                    ?: System.getenv("TEMP")
                    ?: "/tmp"
            return File(tmp)
        }

        /** Android: inject application cache dir instead of host tmp. */
        fun forAndroid(cacheDir: File, maxCaptureChars: Int = 64 * 1024): RealNtfsProcessLauncher =
            RealNtfsProcessLauncher(maxCaptureChars = maxCaptureChars, hostCacheDir = cacheDir)
    }
}

object NtfsNativeTools {
    const val MKNTFS_SO = "librufidmkntfs.so"
    const val STREAM_SO = "librufidntfsstream.so"

    fun resolve(nativeLibraryDir: File, name: String): File {
        val file = File(nativeLibraryDir, name)
        if (!file.isFile) {
            throw IOException("Missing native tool $name under ${nativeLibraryDir.absolutePath}")
        }
        if (!file.canExecute()) {
            file.setExecutable(true, false)
        }
        if (!file.canExecute()) {
            throw IOException("Native tool is not executable: ${file.absolutePath}")
        }
        return file
    }

    /**
     * Resolve from APK-installed nativeLibraryDir, or extract lib/<abi>/name from the APK
     * into [extractDir] when the platform leaves native libs unextracted.
     */
    fun resolveFromContext(
        nativeLibraryDir: File,
        apkPath: String?,
        extractDir: File,
        name: String,
        preferredAbis: Array<String>,
    ): File {
        val direct = File(nativeLibraryDir, name)
        if (direct.isFile) {
            return resolve(nativeLibraryDir, name)
        }
        if (apkPath.isNullOrBlank()) {
            throw IOException("Missing native tool $name and APK path unavailable.")
        }
        extractDir.mkdirs()
        val out = File(extractDir, name)
        java.util.zip.ZipFile(File(apkPath)).use { zip ->
            val entry = preferredAbis.asSequence()
                .map { abi -> zip.getEntry("lib/$abi/$name") }
                .firstOrNull { it != null }
                ?: throw IOException("APK lacks $name for abis=${preferredAbis.joinToString()}")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        out.setExecutable(true, false)
        if (!out.isFile || !out.canExecute()) {
            throw IOException("Failed to extract executable tool: ${out.absolutePath}")
        }
        return out
    }
}
