package io.github.rufid.core

import io.github.rufid.format.Fat32Layout
import io.github.rufid.partition.PartitionPlan
import java.io.IOException
import kotlin.math.ceil

internal class Fat32IsoTreeWriter(
    private val blockDevice: SeekableBlockDevice,
    private val partitionPlan: PartitionPlan,
    private val layout: Fat32Layout,
    volumeLabel: String,
) {
    private val clusterSize = layout.clusterSizeBytes.toInt()
    private val root = Fat32DirectoryNode("", null)
    private val fatEntries = mutableMapOf<Int, Int>()
    private val fileClusterRanges = mutableListOf<Fat32ClusterRange>()
    private var nextFreeCluster = 3
    private var prepared = false
    private val normalizedVolumeLabel = volumeLabel.uppercase()
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '_' || it == '-' }
        .take(FAT32_SHORT_NAME_LENGTH)
        .padEnd(FAT32_SHORT_NAME_LENGTH, ' ')

    val freeClusterCount: Long
        get() {
            check(prepared) { "FAT32 ISO tree must be prepared before reading allocation state." }
            return layout.clusterCount - (nextFreeCluster.toLong() - layout.rootCluster.toLong())
        }

    val nextFreeClusterHint: Long
        get() = if (freeClusterCount == 0L) 0xffff_ffffL else nextFreeCluster.toLong()

    fun write(
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
        onProgress: (Progress) -> Unit,
    ) {
        prepare(files)
        writePrepared(files, cancellationToken, onProgress)
    }

    fun prepare(files: List<ExtractableIsoFile>) {
        check(!prepared) { "FAT32 ISO tree is already prepared." }
        for (file in files) addFile(file)
        assignDirectoryClusters(root, fixedFirstCluster = layout.rootCluster)
        assignFileClusters(root)
        prepared = true
    }

    fun writePrepared(
        files: List<ExtractableIsoFile>,
        cancellationToken: CancellationToken,
        onProgress: (Progress) -> Unit,
    ) {
        check(prepared) { "FAT32 ISO tree must be prepared before writing." }
        writeMetadata(cancellationToken)
        var done = 0L
        // Final writable set total (same set prepared into the tree).
        val total = files.sumOf { it.size }
        for (file in root.walkFiles()) {
            done = copyFile(file, cancellationToken, done, total, onProgress)
        }
        writeMetadata(cancellationToken)
    }

    private fun writeMetadata(cancellationToken: CancellationToken) {
        writeFat(cancellationToken)
        writeDirectory(root, cancellationToken)
    }

    private fun addFile(source: ExtractableIsoFile) {
        val parts = validatePath(source.path)
        var directory = root
        for (part in parts.dropLast(1)) {
            val key = part.normalizeDirectoryKey()
            require(directory.files.none { it.name.equals(part, ignoreCase = true) }) {
                "ISO path conflicts with a file: ${source.path}"
            }
            directory = directory.directories.getOrPut(key) {
                Fat32DirectoryNode(part, directory, directory.allocateShortName(part))
            }
        }
        val fileName = parts.last()
        require(directory.directories[fileName.normalizeDirectoryKey()] == null) {
            "ISO path conflicts with a directory: ${source.path}"
        }
        require(directory.files.none { it.name.equals(fileName, ignoreCase = true) }) {
            "ISO contains a duplicate path: ${source.path}"
        }
        directory.files += Fat32FileNode(fileName, source, directory.allocateShortName(fileName))
    }

    private fun validatePath(path: String): List<String> {
        require(path.isNotBlank()) { "ISO file path is empty." }
        require('\\' !in path) { "ISO file path contains a backslash: $path" }
        val parts = path.split('/')
        require(parts.none { it.isEmpty() }) { "ISO file path contains an empty segment: $path" }
        for (part in parts) {
            require(part.length <= FAT32_MAX_LONG_NAME_CODE_UNITS) { "ISO file name is too long for FAT32: $path" }
            require(part != "." && part != "..") { "ISO file path contains a traversal segment: $path" }
            require(part.last() != '.' && part.last() != ' ') { "ISO file path has an unsafe ending: $path" }
            require(part.none { it.code < 32 || it in FAT32_FORBIDDEN_NAME_CHARS }) {
                "ISO file path contains a FAT32-forbidden character: $path"
            }
        }
        return parts
    }

    private fun assignDirectoryClusters(directory: Fat32DirectoryNode, fixedFirstCluster: Int? = null) {
        val entryCount = directory.reservedEntrySlots() +
            (if (directory.parent == null) 1 else 0) +
            directory.directories.values.sumOf { it.entrySlots() } +
            directory.files.sumOf { it.entrySlots() }
        val clusters = maxOf(1, ceil((entryCount * FAT32_DIRECTORY_ENTRY_SIZE).toDouble() / clusterSize.toDouble()).toInt())
        directory.clusters = if (fixedFirstCluster != null) {
            listOf(fixedFirstCluster) + allocateClusters(clusters - 1)
        } else {
            allocateClusters(clusters)
        }
        markChain(directory.clusters)
        for (child in directory.directories.values) assignDirectoryClusters(child)
    }

    private fun assignFileClusters(directory: Fat32DirectoryNode) {
        for (file in directory.files) {
            val clusters = clusterCountForSize(file.source.size)
            if (clusters > 0) {
                val range = allocateClusterRange(clusters)
                file.firstCluster = range.firstCluster
                file.clusterCount = range.clusterCount
                fileClusterRanges += range
            }
        }
        for (child in directory.directories.values) assignFileClusters(child)
    }

    private fun allocateClusters(count: Int): List<Int> =
        if (count == 0) {
            emptyList()
        } else {
            val range = allocateClusterRange(count)
            (0 until count).map { range.firstCluster + it }
        }

    private fun allocateClusterRange(count: Int): Fat32ClusterRange {
        require(count >= 0)
        val firstCluster = nextFreeCluster
        val lastCluster = firstCluster.toLong() + count.toLong() - 1L
        require(lastCluster < layout.clusterCount + 2L) { "FAT32 volume is too small for extracted ISO files." }
        nextFreeCluster += count
        return Fat32ClusterRange(firstCluster, count)
    }

    private fun clusterCountForSize(size: Long): Int {
        val clusters = (size + clusterSize.toLong() - 1L) / clusterSize.toLong()
        require(clusters <= Int.MAX_VALUE) { "FAT32 file requires too many clusters." }
        return clusters.toInt()
    }

    private fun markChain(clusters: List<Int>) {
        for ((index, cluster) in clusters.withIndex()) {
            fatEntries[cluster] = if (index == clusters.lastIndex) FAT32_EOC else clusters[index + 1]
        }
    }

    private fun writeFat(cancellationToken: CancellationToken) {
        val entries = fatEntriesToWrite()
        val fatChunk = ByteArray(FAT_CHUNK_SECTORS * layout.sectorSize)
        for (fatIndex in 0 until layout.fatCount) {
            var sectorOffset = 0L
            var entryIndex = 0
            while (sectorOffset < layout.sectorsPerFat) {
                cancellationToken.throwIfCancelled()
                val chunkSectors = minOf(FAT_CHUNK_SECTORS.toLong(), layout.sectorsPerFat - sectorOffset).toInt()
                val chunkBytes = chunkSectors * layout.sectorSize
                fatChunk.fill(0, 0, chunkBytes)
                entryIndex = writeFatEntriesForChunk(entries, entryIndex, sectorOffset, chunkBytes, fatChunk)
                writeFileRangeFatEntriesForChunk(sectorOffset, chunkBytes, fatChunk)
                val sector = partitionPlan.startSector +
                    layout.reservedSectors +
                    fatIndex.toLong() * layout.sectorsPerFat +
                    sectorOffset
                blockDevice.seek(sector * layout.sectorSize.toLong())
                blockDevice.write(fatChunk, 0, chunkBytes)
                sectorOffset += chunkSectors
            }
        }
    }

    private fun fatEntriesToWrite(): List<Pair<Int, Int>> =
        buildList {
            add(0 to 0x0ffffff8)
            add(1 to 0xffffffff.toInt())
            addAll(fatEntries.map { it.key to it.value })
        }.sortedBy { it.first }

    private fun writeFatEntriesForChunk(
        entries: List<Pair<Int, Int>>,
        startEntryIndex: Int,
        sectorOffset: Long,
        chunkBytes: Int,
        fatChunk: ByteArray,
    ): Int {
        val chunkStartByte = sectorOffset * layout.sectorSize.toLong()
        val chunkEndByte = chunkStartByte + chunkBytes.toLong()
        var entryIndex = startEntryIndex
        while (entryIndex < entries.size) {
            val (cluster, value) = entries[entryIndex]
            val entryByteOffset = cluster.toLong() * FAT_ENTRY_BYTES
            if (entryByteOffset >= chunkEndByte) break
            if (entryByteOffset >= chunkStartByte) {
                putLeInt(fatChunk, (entryByteOffset - chunkStartByte).toInt(), value)
            }
            entryIndex++
        }
        return entryIndex
    }

    private fun writeFileRangeFatEntriesForChunk(
        sectorOffset: Long,
        chunkBytes: Int,
        fatChunk: ByteArray,
    ) {
        val chunkStartByte = sectorOffset * layout.sectorSize.toLong()
        val chunkEndByte = chunkStartByte + chunkBytes.toLong()
        val chunkStartCluster = chunkStartByte / FAT_ENTRY_BYTES
        val chunkEndCluster = (chunkEndByte / FAT_ENTRY_BYTES) - 1L
        for (range in fileClusterRanges) {
            var cluster = maxOf(range.firstCluster.toLong(), chunkStartCluster)
            val endCluster = minOf(range.lastCluster.toLong(), chunkEndCluster)
            while (cluster <= endCluster) {
                val value = if (cluster == range.lastCluster.toLong()) FAT32_EOC else (cluster + 1L).toInt()
                putLeInt(fatChunk, (cluster * FAT_ENTRY_BYTES - chunkStartByte).toInt(), value)
                cluster++
            }
        }
    }

    private fun writeDirectory(directory: Fat32DirectoryNode, cancellationToken: CancellationToken) {
        val bytes = ByteArray(directory.clusters.size * clusterSize)
        var offset = 0
        if (directory.parent == null) {
            normalizedVolumeLabel.encodeToByteArray().copyInto(bytes, offset)
            bytes[offset + FAT32_ATTRIBUTE_OFFSET] = FAT32_VOLUME_LABEL_ATTRIBUTE
            offset += FAT32_DIRECTORY_ENTRY_SIZE
        } else {
            offset += writeShortEntry(bytes, offset, FAT32_DOT_SHORT_NAME, isDirectory = true, firstCluster = directory.clusters.first(), size = 0)
            offset += writeEntry(
                bytes,
                offset,
                "..",
                FAT32_DOT_DOT_SHORT_NAME,
                isDirectory = true,
                firstCluster = directory.parentClusterForDotDot(),
                size = 0,
            )
        }
        for (child in directory.directories.values) {
            offset += writeEntry(bytes, offset, child.name, child.shortName, isDirectory = true, firstCluster = child.clusters.first(), size = 0)
        }
        for (file in directory.files) {
            offset += writeEntry(
                bytes,
                offset,
                file.name,
                file.shortName,
                isDirectory = false,
                firstCluster = if (file.clusterCount == 0) 0 else file.firstCluster,
                size = file.source.size,
            )
        }
        writeClusters(directory.clusters, bytes, cancellationToken)
        for (child in directory.directories.values) writeDirectory(child, cancellationToken)
    }

    /**
     * Copies one file cluster-by-cluster. Reports monotonic [Progress.Phase.Writing] after each
     * successful cluster write using actual file bytes (not padded cluster size).
     * Cancellation requested from [onProgress] aborts before the next cluster write.
     * Does not emit [Progress.Phase.Finished] (caller does that only after flush success).
     */
    private fun copyFile(
        file: Fat32FileNode,
        cancellationToken: CancellationToken,
        bytesDoneSoFar: Long,
        bytesTotal: Long,
        onProgress: (Progress) -> Unit,
    ): Long {
        if (file.clusterCount == 0) return bytesDoneSoFar
        val bytes = ByteArray(clusterSize)
        var readOffset = 0L
        var cluster = file.firstCluster
        var bytesDone = bytesDoneSoFar
        repeat(file.clusterCount) {
            cancellationToken.throwIfCancelled()
            bytes.fill(0)
            val expected = minOf(clusterSize.toLong(), file.source.size - readOffset).toInt()
            var filled = 0
            while (filled < expected) {
                val read = file.source.readAt(readOffset + filled, bytes, filled, expected - filled)
                if (read <= 0) throw IOException("ISO file ended early: ${file.source.path}")
                filled += read
            }
            blockDevice.seek(clusterOffset(cluster))
            blockDevice.write(bytes, 0, bytes.size)
            readOffset += expected
            cluster++
            bytesDone += expected
            require(bytesDone in 0L..bytesTotal) {
                "Progress out of range: bytesDone=$bytesDone bytesTotal=$bytesTotal"
            }
            onProgress(Progress(bytesDone, bytesTotal, Progress.Phase.Writing))
            // Allow cancel from the progress callback before the next cluster write.
            cancellationToken.throwIfCancelled()
        }
        return bytesDone
    }

    private fun writeClusters(clusters: List<Int>, bytes: ByteArray, cancellationToken: CancellationToken) {
        val clusterBytes = ByteArray(clusterSize)
        var offset = 0
        for (cluster in clusters) {
            cancellationToken.throwIfCancelled()
            blockDevice.seek(clusterOffset(cluster))
            if (offset == 0 && bytes.size == clusterSize) {
                blockDevice.write(bytes, 0, clusterSize)
            } else {
                bytes.copyInto(clusterBytes, 0, offset, offset + clusterSize)
                blockDevice.write(clusterBytes, 0, clusterSize)
            }
            offset += clusterSize
        }
    }

    private fun writeEntry(
        bytes: ByteArray,
        offset: Int,
        name: String,
        shortName: ByteArray,
        isDirectory: Boolean,
        firstCluster: Int,
        size: Long,
    ): Int {
        val longNameBytes = writeLongNameEntries(bytes, offset, name, shortName)
        writeShortEntry(bytes, offset + longNameBytes, shortName, isDirectory, firstCluster, size)
        return longNameBytes + FAT32_DIRECTORY_ENTRY_SIZE
    }

    private fun writeShortEntry(
        bytes: ByteArray,
        offset: Int,
        shortName: ByteArray,
        isDirectory: Boolean,
        firstCluster: Int,
        size: Long,
    ): Int {
        shortName.copyInto(bytes, offset)
        bytes[offset + 11] = if (isDirectory) 0x10 else 0x20
        putLeShort(bytes, offset + 20, (firstCluster ushr 16) and 0xffff)
        putLeShort(bytes, offset + 26, firstCluster and 0xffff)
        putLeInt(bytes, offset + 28, size.toInt())
        return FAT32_DIRECTORY_ENTRY_SIZE
    }

    private fun writeLongNameEntries(bytes: ByteArray, offset: Int, name: String, shortName: ByteArray): Int {
        if (!Fat32DirectoryNames.needsLongName(name, shortName)) return 0
        val chars = name.toCharArray().toList()
        val chunks = chars.chunked(FAT32_LONG_NAME_CHARS_PER_ENTRY)
        val checksum = Fat32DirectoryNames.shortNameChecksum(shortName)
        var cursor = offset
        for (chunkIndex in chunks.indices.reversed()) {
            val order = chunkIndex + 1
            bytes[cursor] = (if (chunkIndex == chunks.lastIndex) order or 0x40 else order).toByte()
            bytes[cursor + 11] = 0x0F
            bytes[cursor + 12] = 0
            bytes[cursor + 13] = checksum.toByte()
            putLeShort(bytes, cursor + 26, 0)
            writeLongNameChunk(bytes, cursor, chunks[chunkIndex], chunkIndex == chunks.lastIndex)
            cursor += FAT32_DIRECTORY_ENTRY_SIZE
        }
        return chunks.size * FAT32_DIRECTORY_ENTRY_SIZE
    }

    private fun writeLongNameChunk(bytes: ByteArray, offset: Int, chars: List<Char>, isLastChunk: Boolean) {
        val codeUnits = MutableList(FAT32_LONG_NAME_CHARS_PER_ENTRY) { 0xffff }
        for (index in chars.indices) codeUnits[index] = chars[index].code
        if (isLastChunk && chars.size < FAT32_LONG_NAME_CHARS_PER_ENTRY) codeUnits[chars.size] = 0
        var sourceIndex = 0
        sourceIndex = writeLongNameField(bytes, offset + 1, codeUnits, sourceIndex, 5)
        sourceIndex = writeLongNameField(bytes, offset + 14, codeUnits, sourceIndex, 6)
        writeLongNameField(bytes, offset + 28, codeUnits, sourceIndex, 2)
    }

    private fun writeLongNameField(bytes: ByteArray, offset: Int, codeUnits: List<Int>, startIndex: Int, count: Int): Int {
        var sourceIndex = startIndex
        for (fieldIndex in 0 until count) {
            putLeShort(bytes, offset + fieldIndex * 2, codeUnits[sourceIndex])
            sourceIndex++
        }
        return sourceIndex
    }

    private fun Fat32DirectoryNode.parentClusterForDotDot(): Int =
        if (parent?.parent == null) 0 else parent.clusters.first()

    private fun clusterOffset(cluster: Int): Long {
        val dataSector = partitionPlan.startSector + layout.reservedSectors + layout.fatCount.toLong() * layout.sectorsPerFat
        val sector = dataSector + (cluster.toLong() - 2L) * layout.sectorsPerCluster.toLong()
        return sector * layout.sectorSize.toLong()
    }

    private fun putLeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private companion object {
        const val FAT_CHUNK_SECTORS = 128
        const val FAT32_SHORT_NAME_LENGTH = 11
        const val FAT32_ATTRIBUTE_OFFSET = 11
        const val FAT32_VOLUME_LABEL_ATTRIBUTE: Byte = 0x08
        const val FAT32_MAX_LONG_NAME_CODE_UNITS = 255
        val FAT32_FORBIDDEN_NAME_CHARS = setOf('"', '*', ':', '<', '>', '?', '|')
        const val FAT_ENTRY_BYTES = 4L
    }

}
