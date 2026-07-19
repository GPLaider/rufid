package io.github.rufid.ntfs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefixed binary protocol for [librufidntfsstream.so].
 * Paths are validated; never shell-escaped or concatenated into shell strings.
 */
object NtfsStreamProtocol {
    const val MODE_POPULATE: Int = 1
    const val MODE_VERIFY: Int = 2
    const val KIND_END: Int = 0
    const val KIND_DIR: Int = 1
    const val KIND_FILE: Int = 2
    const val MAX_PATH_BYTES: Int = 4096
    /** Max UTF-16 code units per path component (ntfs_create u8 name_len). */
    const val MAX_UCS_NAME: Int = 255
    /** Max file size accepted for s64 ntfs_attr_pwrite. */
    const val MAX_FILE_SIZE: Long = Long.MAX_VALUE

    /**
     * Windows-forbidden / control bytes (mirrors rufid_ntfs_stream.c is_windows_forbidden_char).
     * Path separator '/' is allowed only as a segment delimiter (skipped in the byte walk).
     */
    private fun isWindowsForbiddenByte(c: Int): Boolean {
        if (c < 0x20 || c == 0x7f) return true
        return when (c) {
            '\\'.code, '/'.code, ':'.code, '*'.code, '?'.code,
            '"'.code, '<'.code, '>'.code, '|'.code,
            -> true
            else -> false
        }
    }

    /** DOS device base names; optional extension (mirrors native is_dos_device_name). */
    private fun isDosDeviceName(seg: String): Boolean {
        val base = seg.substringBefore('.')
        if (base.isEmpty()) return false
        val upper = base.uppercase()
        if (upper == "CON" || upper == "PRN" || upper == "AUX" || upper == "NUL") return true
        if (base.length == 4) {
            val p = upper
            if ((p.startsWith("COM") || p.startsWith("LPT")) && p[3] in '1'..'9') return true
        }
        return false
    }

    /**
     * Path safety aligned with native rufid_ntfs_stream.c path_is_safe:
     * absolute '/', control/NUL, Windows-forbidden chars, empty/dot/dotdot segments,
     * trailing component dot or space, DOS device names, max path and per-component UCS length.
     * Never sanitize input; callers must pass the original path.
     */
    fun requireSafeRelativePath(path: String) {
        if (path.isEmpty()) throw IOException("NTFS path is empty.")
        val utf8 = path.toByteArray(Charsets.UTF_8)
        if (utf8.size > MAX_PATH_BYTES) throw IOException("NTFS path too long: ${utf8.size}")
        if (path.startsWith('/')) throw IOException("NTFS path is absolute: $path")
        // Byte walk (same as native UTF-8 path checks).
        for (b in utf8) {
            val c = b.toInt() and 0xff
            if (c == '/'.code) continue
            if (c == 0 || isWindowsForbiddenByte(c)) {
                throw IOException("NTFS path contains forbidden/control character: $path")
            }
        }
        val segments = path.split('/')
        if (segments.isEmpty()) throw IOException("NTFS path has no segments.")
        segments.forEach { seg ->
            if (seg.isEmpty() || seg == "." || seg == "..") {
                throw IOException("NTFS path has empty/dot/dotdot segment: $path")
            }
            // Trailing '.' or ' ' (native last-char check).
            val last = seg.last()
            if (last == '.' || last == ' ') {
                throw IOException("NTFS path component has trailing dot/space: $path")
            }
            if (isDosDeviceName(seg)) {
                throw IOException("NTFS path uses DOS device name: $path")
            }
            // UTF-16 code units approximate ntfschar count for BMP names (native u8 cap 255).
            if (seg.length > MAX_UCS_NAME) {
                throw IOException("NTFS path component exceeds $MAX_UCS_NAME UCS units: $path")
            }
        }
    }

    fun requireSafeFileSize(size: Long) {
        if (size < 0L) throw IOException("NTFS file size is negative.")
        if (size > MAX_FILE_SIZE) throw IOException("NTFS file size exceeds INT64_MAX.")
    }

    fun writeMode(out: OutputStream, mode: Int) {
        require(mode == MODE_POPULATE || mode == MODE_VERIFY) { "Invalid mode $mode" }
        out.write(mode)
    }

    fun writeDir(out: OutputStream, path: String) {
        requireSafeRelativePath(path)
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        if (pathBytes.size > MAX_PATH_BYTES) throw IOException("NTFS path UTF-8 too long.")
        out.write(KIND_DIR)
        writeU16Le(out, pathBytes.size)
        out.write(pathBytes)
    }

    fun writeFileHeader(out: OutputStream, path: String, size: Long) {
        requireSafeRelativePath(path)
        requireSafeFileSize(size)
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        if (pathBytes.size > MAX_PATH_BYTES) throw IOException("NTFS path UTF-8 too long.")
        out.write(KIND_FILE)
        writeU16Le(out, pathBytes.size)
        out.write(pathBytes)
        writeU64Le(out, size)
    }

    fun writeEnd(out: OutputStream) {
        out.write(KIND_END)
    }

    /** Encode a complete populate/verify session into a byte array (for tests). */
    fun encodeSession(
        mode: Int,
        dirs: List<String>,
        files: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        writeMode(bos, mode)
        dirs.forEach { writeDir(bos, it) }
        files.forEach { (path, data) ->
            writeFileHeader(bos, path, data.size.toLong())
            bos.write(data)
        }
        writeEnd(bos)
        return bos.toByteArray()
    }

    fun writeU16Le(out: OutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    fun writeU64Le(out: OutputStream, value: Long) {
        var v = value
        repeat(8) {
            out.write((v and 0xffL).toInt())
            v = v ushr 8
        }
    }

    fun readU16Le(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) throw IOException("Truncated u16.")
        return b0 or (b1 shl 8)
    }

    fun readU64Le(input: InputStream): Long {
        var v = 0L
        repeat(8) { i ->
            val b = input.read()
            if (b < 0) throw IOException("Truncated u64.")
            v = v or (b.toLong() shl (8 * i))
        }
        return v
    }

    fun leBuffer(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
}
