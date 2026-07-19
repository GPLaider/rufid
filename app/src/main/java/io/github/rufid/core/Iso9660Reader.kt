package io.github.rufid.core

import java.io.Closeable
import java.io.IOException

interface SeekableByteSource {
    val sizeBytes: Long
    fun readAt(byteOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

class ByteArraySeekableByteSource(
    private val bytes: ByteArray,
) : SeekableByteSource {
    override val sizeBytes: Long = bytes.size.toLong()

    override fun readAt(byteOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (byteOffset >= bytes.size) return -1
        val start = byteOffset.toInt()
        val available = bytes.size - start
        val count = minOf(length, available)
        bytes.copyInto(buffer, offset, start, start + count)
        return count
    }
}

data class Iso9660File(
    val path: String,
    val extentSector: Int,
    val size: Long,
)

class ExtractableIsoFile(
    val path: String,
    val size: Long,
    private val reader: (fileOffset: Long, buffer: ByteArray, outputOffset: Int, length: Int) -> Int,
    private val onClose: () -> Unit = {},
) : Closeable {
    fun readAt(fileOffset: Long, buffer: ByteArray, outputOffset: Int, length: Int): Int =
        reader(fileOffset, buffer, outputOffset, length)

    override fun close() = onClose()
}

class Iso9660Reader(
    private val source: SeekableByteSource,
) {
    fun listFiles(): List<Iso9660File> {
        val descriptor = readSector(16)
        require(descriptor[0].toInt() == 1 && descriptor.decodeAscii(1, 5) == "CD001") {
            "Primary ISO9660 volume descriptor not found."
        }
        val root = DirectoryRecord.parse(descriptor, 156)
        val files = mutableListOf<Iso9660File>()
        readDirectory(root, prefix = "", files)
        return files.sortedBy { it.path }
    }

    fun readFile(file: Iso9660File, buffer: ByteArray, fileOffset: Long, outputOffset: Int, length: Int): Int {
        if (fileOffset >= file.size) return -1
        val count = minOf(length.toLong(), file.size - fileOffset).toInt()
        val read = source.readAt(file.extentSector.toLong() * ISO_SECTOR_SIZE + fileOffset, buffer, outputOffset, count)
        if (read < count) throw IOException("ISO file ended early: ${file.path}")
        return read
    }

    private fun readDirectory(record: DirectoryRecord, prefix: String, files: MutableList<Iso9660File>) {
        val bytes = ByteArray(record.size.toInt())
        readFully(record.extentSector.toLong() * ISO_SECTOR_SIZE, bytes, 0, bytes.size)
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xff
            if (length == 0) {
                offset = ((offset / ISO_SECTOR_SIZE) + 1) * ISO_SECTOR_SIZE
                continue
            }
            val entry = DirectoryRecord.parse(bytes, offset)
            val name = normalizeEntryName(entry.name)
            if (name.isNotBlank() && name != "." && name != "..") {
                val path = if (prefix.isBlank()) name else "$prefix/$name"
                if (entry.isDirectory) {
                    readDirectory(entry, path, files)
                } else {
                    files += Iso9660File(path = path, extentSector = entry.extentSector, size = entry.size)
                }
            }
            offset += length
        }
    }

    private fun readSector(sector: Int): ByteArray =
        ByteArray(ISO_SECTOR_SIZE).also { readFully(sector.toLong() * ISO_SECTOR_SIZE, it, 0, it.size) }

    private fun readFully(byteOffset: Long, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = source.readAt(byteOffset + done, buffer, offset + done, length - done)
            if (read <= 0) throw IOException("ISO source ended early at ${byteOffset + done}")
            done += read
        }
    }

    private fun normalizeEntryName(name: String): String =
        when (name) {
            "\u0000" -> "."
            "\u0001" -> ".."
            else -> name.substringBefore(';').lowercase()
        }

    private data class DirectoryRecord(
        val extentSector: Int,
        val size: Long,
        val flags: Int,
        val name: String,
    ) {
        val isDirectory: Boolean
            get() = flags and 0x02 != 0

        companion object {
            fun parse(bytes: ByteArray, offset: Int): DirectoryRecord {
                val nameLength = bytes[offset + 32].toInt() and 0xff
                return DirectoryRecord(
                    extentSector = readLeInt(bytes, offset + 2),
                    size = readLeUInt(bytes, offset + 10),
                    flags = bytes[offset + 25].toInt() and 0xff,
                    name = bytes.copyOfRange(offset + 33, offset + 33 + nameLength).decodeToString(),
                )
            }

            private fun readLeInt(bytes: ByteArray, offset: Int): Int =
                (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xff) shl 24)

            private fun readLeUInt(bytes: ByteArray, offset: Int): Long =
                (bytes[offset].toInt() and 0xff).toLong() or
                    ((bytes[offset + 1].toInt() and 0xff).toLong() shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff).toLong() shl 16) or
                    ((bytes[offset + 3].toInt() and 0xff).toLong() shl 24)
        }
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).decodeToString()

    private companion object {
        const val ISO_SECTOR_SIZE = 2048
    }
}
