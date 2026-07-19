package io.github.rufid.core

import kotlin.math.ceil

internal class Fat32DirectoryNode(
    val name: String,
    val parent: Fat32DirectoryNode?,
    val shortName: ByteArray = ByteArray(11) { 0x20.toByte() },
) {
    val directories: MutableMap<String, Fat32DirectoryNode> = linkedMapOf()
    val files: MutableList<Fat32FileNode> = mutableListOf()
    var clusters: List<Int> = emptyList()
    private val usedShortNames = mutableSetOf<String>()

    fun walkFiles(): List<Fat32FileNode> =
        files + directories.values.flatMap { it.walkFiles() }

    fun allocateShortName(name: String): ByteArray =
        Fat32DirectoryNames.createShortName(name, usedShortNames)

    fun entrySlots(): Int =
        reservedEntrySlots() + if (Fat32DirectoryNames.needsLongName(name, shortName)) {
            ceil(name.length.toDouble() / FAT32_LONG_NAME_CHARS_PER_ENTRY.toDouble()).toInt()
        } else {
            0
        } + 1

    fun reservedEntrySlots(): Int = if (parent == null) 0 else 2
}

internal class Fat32FileNode(
    val name: String,
    val source: ExtractableIsoFile,
    val shortName: ByteArray,
) {
    var firstCluster: Int = 0
    var clusterCount: Int = 0

    fun entrySlots(): Int =
        1 + if (Fat32DirectoryNames.needsLongName(name, shortName)) {
            ceil(name.length.toDouble() / FAT32_LONG_NAME_CHARS_PER_ENTRY.toDouble()).toInt()
        } else {
            0
        }
}

internal data class Fat32ClusterRange(
    val firstCluster: Int,
    val clusterCount: Int,
) {
    val lastCluster: Int
        get() = firstCluster + clusterCount - 1
}

internal object Fat32DirectoryNames {
    fun needsLongName(name: String, shortName: ByteArray): Boolean =
        name.uppercase() != shortNameDisplay(shortName)

    fun shortNameChecksum(shortName: ByteArray): Int {
        var sum = 0
        for (byte in shortName) {
            sum = (((sum and 1) shl 7) + (sum ushr 1) + (byte.toInt() and 0xff)) and 0xff
        }
        return sum
    }

    fun createShortName(name: String, usedShortNames: MutableSet<String>): ByteArray {
        val base = name.substringBeforeLast('.').uppercase().filterShortName().ifBlank { "FILE" }
        val extension = name.substringAfterLast('.', "").uppercase().filterShortName().take(3)
        fun candidate(index: Int?): ByteArray {
            val suffix = index?.let { "~$it" }.orEmpty()
            val baseLimit = 8 - suffix.length
            val shortBase = (base.take(baseLimit) + suffix).padEnd(8, ' ')
            val shortExtension = extension.padEnd(3, ' ')
            return (shortBase + shortExtension).encodeToByteArray()
        }
        val first = candidate(null)
        if (usedShortNames.add(first.decodeToString())) return first
        for (index in 1..999999) {
            val next = candidate(index)
            if (usedShortNames.add(next.decodeToString())) return next
        }
        error("Unable to allocate FAT short name for $name")
    }

    private fun shortNameDisplay(shortName: ByteArray): String {
        val base = shortName.copyOfRange(0, 8).decodeToString().trimEnd()
        val extension = shortName.copyOfRange(8, 11).decodeToString().trimEnd()
        return if (extension.isBlank()) base else "$base.$extension"
    }

    private fun String.filterShortName(): String =
        filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '$' || it == '~' }
}

internal const val FAT32_DIRECTORY_ENTRY_SIZE = 32
internal const val FAT32_LONG_NAME_CHARS_PER_ENTRY = 13
internal const val FAT32_EOC = 0x0fffffff
internal val FAT32_DOT_SHORT_NAME = ".          ".encodeToByteArray()
internal val FAT32_DOT_DOT_SHORT_NAME = "..         ".encodeToByteArray()

internal fun String.normalizeDirectoryKey(): String =
    lowercase()
