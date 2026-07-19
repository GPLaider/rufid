package io.github.rufid.core

import java.io.IOException

internal class UdfReader(
    private val source: SeekableByteSource,
) {
    fun listFiles(): List<ExtractableIsoFile>? {
        if (!hasUdfVolumeRecognitionSequence()) return null
        val anchor = readAnchor() ?: return null
        val descriptors = readVolumeDescriptors(anchor)
        val partition = descriptors.partition ?: return null
        val fileSet = readFileSetDescriptor(descriptors.fileSetDescriptor, partition)
        val root = readFileEntry(fileSet.rootIcb, partition)
        val files = mutableListOf<ExtractableIsoFile>()
        readDirectory(root, partition, prefix = "", files)
        return files.sortedBy { it.path }
    }

    private fun hasUdfVolumeRecognitionSequence(): Boolean {
        var sawBegin = false
        var sawUdf = false
        for (sector in 16 until 32) {
            val bytes = readBytesOrNull(sector.toLong() * UDF_SECTOR_SIZE, UDF_SECTOR_SIZE) ?: return false
            val id = bytes.copyOfRange(1, 6).decodeToString()
            if (id == "BEA01") sawBegin = true
            if (id == "NSR02" || id == "NSR03") sawUdf = true
            if (id == "TEA01") return sawBegin && sawUdf
        }
        return sawBegin && sawUdf
    }

    private fun readAnchor(): UdfExtent? {
        val candidates = listOf(
            256L,
            source.sizeBytes / UDF_SECTOR_SIZE - 1,
            source.sizeBytes / UDF_SECTOR_SIZE - 257,
        ).filter { it >= 0 }
        for (sector in candidates) {
            val bytes = readBytesOrNull(sector * UDF_SECTOR_SIZE, UDF_SECTOR_SIZE) ?: continue
            if (bytes.readLeUShort(0) == UDF_TAG_ANCHOR) return bytes.readUdfExtent(16)
        }
        return null
    }

    private fun readVolumeDescriptors(mainSequence: UdfExtent): UdfVolumeDescriptors {
        var partition: UdfPartition? = null
        var fileSetDescriptor: UdfLongAd? = null
        val sectors = (mainSequence.length + UDF_SECTOR_SIZE - 1) / UDF_SECTOR_SIZE
        for (index in 0 until sectors) {
            val bytes = readBytes(mainSequence.location.toLong() * UDF_SECTOR_SIZE + index.toLong() * UDF_SECTOR_SIZE, UDF_SECTOR_SIZE)
            when (bytes.readLeUShort(0)) {
                UDF_TAG_PARTITION -> {
                    partition = UdfPartition(
                        number = bytes.readLeUShort(22),
                        startSector = bytes.readLeUInt(188),
                        length = bytes.readLeUInt(192),
                    )
                }
                UDF_TAG_LOGICAL_VOLUME -> fileSetDescriptor = bytes.readUdfLongAd(248)
                UDF_TAG_TERMINATING -> break
            }
        }
        return UdfVolumeDescriptors(partition, requireNotNull(fileSetDescriptor) { "UDF logical volume has no file set descriptor." })
    }

    private fun readFileSetDescriptor(longAd: UdfLongAd, partition: UdfPartition): UdfFileSetDescriptor {
        val bytes = readBytes(partition.byteOffset(longAd.logicalBlock), minOf(longAd.length, UDF_SECTOR_SIZE))
        require(bytes.readLeUShort(0) == UDF_TAG_FILE_SET) { "UDF file set descriptor not found." }
        return UdfFileSetDescriptor(rootIcb = bytes.readUdfLongAd(400))
    }

    private fun readFileEntry(longAd: UdfLongAd, partition: UdfPartition): UdfFileEntry {
        val bytes = readBytes(partition.byteOffset(longAd.logicalBlock), minOf(longAd.length, UDF_SECTOR_SIZE))
        val tag = bytes.readLeUShort(0)
        require(tag == UDF_TAG_FILE_ENTRY || tag == UDF_TAG_EXTENDED_FILE_ENTRY) { "Unsupported UDF file entry descriptor: $tag" }
        val flags = bytes.readLeUShort(34)
        val allocationType = flags and 0x0007
        val fileType = bytes[27].toInt() and 0xff
        val infoLength = bytes.readLeULong(56)
        val lengthOfExtendedAttributes: Int
        val lengthOfAllocationDescriptors: Int
        val allocationOffset: Int
        if (tag == UDF_TAG_FILE_ENTRY) {
            lengthOfExtendedAttributes = bytes.readLeUInt(168).toInt()
            lengthOfAllocationDescriptors = bytes.readLeUInt(172).toInt()
            allocationOffset = 176 + lengthOfExtendedAttributes
        } else {
            lengthOfExtendedAttributes = bytes.readLeUInt(208).toInt()
            lengthOfAllocationDescriptors = bytes.readLeUInt(212).toInt()
            allocationOffset = 216 + lengthOfExtendedAttributes
        }
        val allocationBytes = bytes.copyOfRange(allocationOffset, allocationOffset + lengthOfAllocationDescriptors)
        return UdfFileEntry(
            fileType = fileType,
            informationLength = infoLength,
            extents = parseAllocationDescriptors(allocationType, allocationBytes, partition),
            embedded = if (allocationType == UDF_ALLOCATION_EMBEDDED) allocationBytes else null,
        )
    }

    private fun parseAllocationDescriptors(allocationType: Int, bytes: ByteArray, partition: UdfPartition): List<UdfFileExtent> =
        when (allocationType) {
            UDF_ALLOCATION_SHORT -> bytes.indices.indicesStep(8).mapNotNull { offset ->
                val rawLength = bytes.readLeUInt(offset)
                val length = rawLength and UDF_EXTENT_LENGTH_MASK
                if (length == 0L) null else UdfFileExtent(partition.byteOffset(bytes.readLeUInt(offset + 4)), length)
            }
            UDF_ALLOCATION_LONG -> bytes.indices.indicesStep(16).mapNotNull { offset ->
                val rawLength = bytes.readLeUInt(offset)
                val length = rawLength and UDF_EXTENT_LENGTH_MASK
                if (length == 0L) null else UdfFileExtent(partition.byteOffset(bytes.readLeUInt(offset + 4)), length)
            }
            UDF_ALLOCATION_EMBEDDED -> emptyList()
            else -> throw UnsupportedOperationException("Unsupported UDF allocation descriptor type: $allocationType")
        }

    private fun readDirectory(
        directory: UdfFileEntry,
        partition: UdfPartition,
        prefix: String,
        output: MutableList<ExtractableIsoFile>,
    ) {
        val bytes = directory.readAll(maxBytes = UDF_MAX_DIRECTORY_BYTES)
        var offset = 0
        while (offset + 38 <= bytes.size) {
            val length = bytes[offset].toInt() and 0xff
            if (length == 0) break
            require(bytes.readLeUShort(offset) == UDF_TAG_FILE_IDENTIFIER) { "Invalid UDF file identifier." }
            val characteristics = bytes[offset + 18].toInt() and 0xff
            val nameLength = bytes[offset + 19].toInt() and 0xff
            val implementationUseLength = bytes.readLeUShort(offset + 36)
            val nameOffset = offset + 38 + implementationUseLength
            val nextOffset = offset + align4(38 + implementationUseLength + nameLength)
            if (characteristics and UDF_FILE_CHARACTERISTIC_PARENT == 0 && characteristics and UDF_FILE_CHARACTERISTIC_DELETED == 0) {
                val name = decodeDString(bytes.copyOfRange(nameOffset, nameOffset + nameLength))
                if (name.isNotBlank()) {
                    val childIcb = bytes.readUdfLongAd(offset + 20)
                    if (childIcb.length == 0) {
                        offset = nextOffset
                        continue
                    }
                    val child = readFileEntry(childIcb, partition)
                    val path = if (prefix.isBlank()) name else "$prefix/$name"
                    if (child.isDirectory) {
                        readDirectory(child, partition, path, output)
                    } else {
                        output += ExtractableIsoFile(
                            path = path,
                            size = child.informationLength,
                            reader = { fileOffset, buffer, outputOffset, requestLength ->
                                child.readAt(fileOffset, buffer, outputOffset, requestLength)
                            },
                        )
                    }
                }
            }
            offset = nextOffset
        }
    }

    private fun UdfFileEntry.readAt(fileOffset: Long, buffer: ByteArray, outputOffset: Int, length: Int): Int {
        if (fileOffset >= informationLength) return -1
        val target = minOf(length.toLong(), informationLength - fileOffset).toInt()
        if (embedded != null) {
            val count = minOf(target, embedded.size - fileOffset.toInt())
            embedded.copyInto(buffer, outputOffset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }
        var remainingOffset = fileOffset
        var done = 0
        for (extent in extents) {
            if (remainingOffset >= extent.length) {
                remainingOffset -= extent.length
                continue
            }
            val count = minOf((target - done).toLong(), extent.length - remainingOffset).toInt()
            val read = source.readAt(extent.byteOffset + remainingOffset, buffer, outputOffset + done, count)
            if (read <= 0) throw IOException("UDF file source ended early at ${extent.byteOffset + remainingOffset}")
            done += read
            remainingOffset = 0
            if (done == target) return done
        }
        if (done > 0) return done
        throw IOException("UDF allocation descriptors ended before file data.")
    }

    private fun UdfFileEntry.readAll(maxBytes: Int): ByteArray {
        require(informationLength <= maxBytes) { "UDF directory is too large to read safely." }
        val bytes = ByteArray(informationLength.toInt())
        var done = 0
        while (done < bytes.size) {
            val read = readAt(done.toLong(), bytes, done, bytes.size - done)
            if (read <= 0) throw IOException("UDF directory ended early at $done")
            done += read
        }
        return bytes
    }

    private fun readBytesOrNull(byteOffset: Long, length: Int): ByteArray? =
        if (byteOffset < 0 || byteOffset + length > source.sizeBytes) null else readBytes(byteOffset, length)

    private fun readBytes(byteOffset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var done = 0
        while (done < length) {
            val read = source.readAt(byteOffset + done, bytes, done, length - done)
            if (read <= 0) throw IOException("UDF source ended early at ${byteOffset + done}")
            done += read
        }
        return bytes
    }

    private fun decodeDString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when (bytes[0].toInt() and 0xff) {
            8 -> bytes.copyOfRange(1, bytes.size).decodeToString()
            16 -> buildString {
                var index = 1
                while (index + 1 < bytes.size) {
                    append(((bytes[index].toInt() and 0xff) shl 8 or (bytes[index + 1].toInt() and 0xff)).toChar())
                    index += 2
                }
            }
            else -> bytes.decodeToString()
        }
    }

}
