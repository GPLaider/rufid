package io.github.rufid.core

import java.io.IOException

internal class Fat32Image(private val bytes: ByteArray) {
    private val partitionStart = readLeUInt(454).toInt()
    private val bytesPerSector = readLeUShort(partitionStart * 512 + 11)
    private val sectorsPerCluster = bytes[partitionStart * 512 + 13].toInt() and 0xff
    private val reservedSectors = readLeUShort(partitionStart * 512 + 14)
    private val fatCount = bytes[partitionStart * 512 + 16].toInt() and 0xff
    private val sectorsPerFat = readLeUInt(partitionStart * 512 + 36).toInt()
    private val totalSectors = readLeUInt(partitionStart * 512 + 32)
    private val rootCluster = readLeUInt(partitionStart * 512 + 44).toInt()
    private val fatOffset = (partitionStart + reservedSectors) * bytesPerSector
    private val dataSector = partitionStart + reservedSectors + fatCount * sectorsPerFat

    fun hasMbrFat32Partition(): Boolean =
        bytes[510] == 0x55.toByte() &&
            bytes[511] == 0xAA.toByte() &&
            bytes[450] == 0x0C.toByte() &&
            bytes[partitionStart * 512 + 510] == 0x55.toByte() &&
            bytes[partitionStart * 512 + 511] == 0xAA.toByte()

    fun rootShortNames(): List<String> {
        val directory = clusterBytes(rootCluster)
        val names = mutableListOf<String>()
        var offset = 0
        while (offset + 32 <= directory.size) {
            val first = directory[offset].toInt() and 0xff
            if (first == 0x00) break
            if (first != 0xE5 && directory[offset + 11] != 0x0F.toByte()) {
                names += directory.copyOfRange(offset, offset + 11).decodeToString().trimEnd()
            }
            offset += 32
        }
        return names
    }

    fun rootVolumeLabel(): String? {
        val directory = clusterBytes(rootCluster)
        var offset = 0
        while (offset + 32 <= directory.size) {
            val first = directory[offset].toInt() and 0xff
            if (first == 0x00) return null
            val attributes = directory[offset + 11].toInt() and 0xff
            if (first != 0xE5 && attributes == 0x08) {
                return directory.copyOfRange(offset, offset + 11).decodeToString().trimEnd()
            }
            offset += 32
        }
        return null
    }

    fun fsInfoFreeClusterCount(): Long = readLeUInt(partitionStart * 512 + 512 + 488)

    fun freeClusterCountFromFat(): Long {
        val dataSectors = totalSectors - reservedSectors - fatCount.toLong() * sectorsPerFat
        val clusterCount = dataSectors / sectorsPerCluster
        return (2L until clusterCount + 2L).count { cluster -> fatEntry(cluster.toInt()) == 0 }.toLong()
    }

    fun firstCluster(path: String): Int {
        val parts = path.split('/').filter { it.isNotBlank() }
        var directoryCluster = rootCluster
        for (directory in parts.dropLast(1)) {
            directoryCluster = findEntry(directoryCluster, directory).firstCluster
        }
        return findEntry(directoryCluster, parts.last()).firstCluster
    }

    fun fatEntry(cluster: Int): Int =
        readLeUInt(fatOffset + cluster * 4).toInt() and 0x0fffffff

    fun dotCluster(path: String): Int = specialDirectoryEntryCluster(path, ".")

    fun dotDotCluster(path: String): Int = specialDirectoryEntryCluster(path, "..")

    fun readFile(path: String): ByteArray {
        val parts = path.split('/').filter { it.isNotBlank() }
        var directoryCluster = rootCluster
        for (directory in parts.dropLast(1)) {
            directoryCluster = findEntry(directoryCluster, directory).firstCluster
        }
        val entry = findEntry(directoryCluster, parts.last())
        val result = ByteArray(entry.size.toInt())
        var cluster = entry.firstCluster
        var offset = 0
        while (cluster >= 2 && offset < result.size) {
            val clusterBytes = clusterBytes(cluster)
            val copy = minOf(clusterBytes.size, result.size - offset)
            clusterBytes.copyInto(result, offset, 0, copy)
            offset += copy
            cluster = nextCluster(cluster)
        }
        return result
    }

    private fun specialDirectoryEntryCluster(path: String, entryName: String): Int {
        val parts = path.split('/').filter { it.isNotBlank() }
        var directoryCluster = rootCluster
        for (directory in parts) {
            directoryCluster = findEntry(directoryCluster, directory).firstCluster
        }
        return findEntry(directoryCluster, entryName).firstCluster
    }

    private fun findEntry(directoryCluster: Int, name: String): DirectoryEntry {
        val directory = clusterBytes(directoryCluster)
        var offset = 0
        var pendingLongName = ""
        while (offset + 32 <= directory.size) {
            val first = directory[offset].toInt() and 0xff
            if (first == 0x00) break
            if (first != 0xE5 && directory[offset + 11] == 0x0F.toByte()) {
                pendingLongName = readLongNamePart(directory, offset) + pendingLongName
            } else if (first != 0xE5) {
                val shortName = directory.copyOfRange(offset, offset + 11).decodeToString()
                    .trimEnd()
                    .replace(" ", "")
                val candidate = pendingLongName.ifBlank { shortName }
                if (candidate.equals(name, ignoreCase = true) || shortName == name.replace(".", "").uppercase()) {
                    val high = readLeUShort(offset + 20, directory)
                    val low = readLeUShort(offset + 26, directory)
                    val size = readLeUInt(offset + 28, directory)
                    return DirectoryEntry((high shl 16) or low, size)
                }
                pendingLongName = ""
            }
            offset += 32
        }
        throw IOException("FAT32 entry not found: $name")
    }

    private fun readLongNamePart(directory: ByteArray, offset: Int): String =
        buildString {
            appendUtf16Chars(directory, offset + 1, 5)
            appendUtf16Chars(directory, offset + 14, 6)
            appendUtf16Chars(directory, offset + 28, 2)
        }

    private fun StringBuilder.appendUtf16Chars(bytes: ByteArray, offset: Int, count: Int) {
        for (index in 0 until count) {
            val low = bytes[offset + index * 2].toInt() and 0xff
            val high = bytes[offset + index * 2 + 1].toInt() and 0xff
            val code = low or (high shl 8)
            if (code == 0x0000 || code == 0xffff) return
            append(code.toChar())
        }
    }

    private fun clusterBytes(cluster: Int): ByteArray {
        val sector = dataSector + (cluster - 2) * sectorsPerCluster
        val offset = sector * bytesPerSector
        val size = sectorsPerCluster * bytesPerSector
        return bytes.copyOfRange(offset, offset + size)
    }

    private fun nextCluster(cluster: Int): Int =
        readLeUInt(fatOffset + cluster * 4).toInt() and 0x0fffffff

    private fun readLeUShort(offset: Int, source: ByteArray = bytes): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readLeUInt(offset: Int, source: ByteArray = bytes): Long =
        (source[offset].toInt() and 0xff).toLong() or
            ((source[offset + 1].toInt() and 0xff).toLong() shl 8) or
            ((source[offset + 2].toInt() and 0xff).toLong() shl 16) or
            ((source[offset + 3].toInt() and 0xff).toLong() shl 24)

    private data class DirectoryEntry(val firstCluster: Int, val size: Long)
}
