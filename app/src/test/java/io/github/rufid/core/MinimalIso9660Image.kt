package io.github.rufid.core

internal class MinimalIso9660Image {
    private val files = linkedMapOf<String, FileSpec>()

    fun file(path: String, content: ByteArray, declaredSize: Long = content.size.toLong()): MinimalIso9660Image {
        files[path.lowercase()] = FileSpec(content, declaredSize)
        return this
    }

    fun build(): ByteArray {
        val image = ByteArray(40 * ISO_SECTOR_SIZE)
        val sectorAllocator = SectorAllocator(20)
        val root = DirectoryNode("")
        for ((path, spec) in files) {
            val parts = path.split('/').filter { it.isNotBlank() }
            var node = root
            for (part in parts.dropLast(1)) {
                node = node.directories.getOrPut(part.uppercase()) { DirectoryNode(part.uppercase()) }
            }
            val sector = sectorAllocator.next()
            spec.content.copyInto(image, sector * ISO_SECTOR_SIZE)
            node.files += FileNode(parts.last().uppercase(), sector, spec.declaredSize)
        }
        assignDirectorySectors(root, sectorAllocator)
        writeDirectory(image, root, root.sector, root.sector)
        writePrimaryVolumeDescriptor(image, root)
        return image
    }

    private fun assignDirectorySectors(node: DirectoryNode, sectorAllocator: SectorAllocator) {
        node.sector = sectorAllocator.next()
        for (child in node.directories.values) assignDirectorySectors(child, sectorAllocator)
    }

    private fun writeDirectory(image: ByteArray, node: DirectoryNode, selfSector: Int, parentSector: Int) {
        val offset = selfSector * ISO_SECTOR_SIZE
        var cursor = offset
        cursor += writeDirectoryRecord(image, cursor, selfSector, ISO_SECTOR_SIZE.toLong(), flags = 0x02, id = byteArrayOf(0))
        cursor += writeDirectoryRecord(image, cursor, parentSector, ISO_SECTOR_SIZE.toLong(), flags = 0x02, id = byteArrayOf(1))
        for ((name, child) in node.directories) {
            cursor += writeDirectoryRecord(image, cursor, child.sector, ISO_SECTOR_SIZE.toLong(), flags = 0x02, id = name.encodeToByteArray())
        }
        for (file in node.files) {
            cursor += writeDirectoryRecord(
                image,
                cursor,
                file.sector,
                file.size,
                flags = 0,
                id = "${file.name};1".encodeToByteArray(),
            )
        }
        for (child in node.directories.values) writeDirectory(image, child, child.sector, selfSector)
    }

    private fun writePrimaryVolumeDescriptor(image: ByteArray, root: DirectoryNode) {
        val offset = 16 * ISO_SECTOR_SIZE
        image[offset] = 1
        "CD001".encodeToByteArray().copyInto(image, offset + 1)
        image[offset + 6] = 1
        writeDirectoryRecord(image, offset + 156, root.sector, ISO_SECTOR_SIZE.toLong(), flags = 0x02, id = byteArrayOf(0))
    }

    private fun writeDirectoryRecord(
        image: ByteArray,
        offset: Int,
        sector: Int,
        size: Long,
        flags: Int,
        id: ByteArray,
    ): Int {
        var length = 33 + id.size
        if (length % 2 != 0) length++
        image[offset] = length.toByte()
        image[offset + 1] = 0
        putBothEndianInt(image, offset + 2, sector)
        putBothEndianInt(image, offset + 10, size.toInt())
        image[offset + 25] = flags.toByte()
        image[offset + 28] = 1
        image[offset + 32] = id.size.toByte()
        id.copyInto(image, offset + 33)
        return length
    }

    private fun putBothEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
        bytes[offset + 4] = (value ushr 24).toByte()
        bytes[offset + 5] = (value ushr 16).toByte()
        bytes[offset + 6] = (value ushr 8).toByte()
        bytes[offset + 7] = value.toByte()
    }

    private data class FileSpec(val content: ByteArray, val declaredSize: Long)
    private data class FileNode(val name: String, val sector: Int, val size: Long)
    private data class DirectoryNode(
        val name: String,
        val directories: MutableMap<String, DirectoryNode> = linkedMapOf(),
        val files: MutableList<FileNode> = mutableListOf(),
        var sector: Int = 0,
    )
    private class SectorAllocator(private var sector: Int) {
        fun next(): Int = sector++
    }

    private companion object {
        const val ISO_SECTOR_SIZE = 2048
    }
}
