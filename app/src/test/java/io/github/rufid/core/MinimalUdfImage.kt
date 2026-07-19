package io.github.rufid.core

internal class MinimalUdfImage(
    private val installWimSize: Long,
    private val includeZeroLengthIcbEntry: Boolean = false,
) {
    fun build(): ByteArray {
        val image = ByteArray(520 * UDF_SECTOR_SIZE)
        writeVolumeRecognitionSequence(image)
        writeAnchor(image, sector = 256, sequenceSector = 300)
        writePartitionDescriptor(image, sector = 301, partitionStart = PARTITION_START)
        writeLogicalVolumeDescriptor(image, sector = 302, fileSetLbn = 0)
        writeTag(image, 303, TAG_TERMINATING)

        val rootDir = fid("sources", entryLbn = 3) +
            fid("efi", entryLbn = 5) +
            fid("bootmgr", entryLbn = 9) +
            if (includeZeroLengthIcbEntry) fid("placeholder", entryLbn = 0, entryLength = 0) else byteArrayOf()
        val sourcesDir = fid("install.wim", entryLbn = 13)
        val efiDir = fid("boot", entryLbn = 7)
        val bootDir = fid("bootx64.efi", entryLbn = 11)

        writeFileSetDescriptor(image, lbn = 0, rootLbn = 1)
        writeFileEntry(image, lbn = 1, directory = true, infoLength = rootDir.size.toLong(), dataLbn = 2, data = rootDir)
        writeFileEntry(image, lbn = 3, directory = true, infoLength = sourcesDir.size.toLong(), dataLbn = 4, data = sourcesDir)
        writeFileEntry(image, lbn = 5, directory = true, infoLength = efiDir.size.toLong(), dataLbn = 6, data = efiDir)
        writeFileEntry(image, lbn = 7, directory = true, infoLength = bootDir.size.toLong(), dataLbn = 8, data = bootDir)
        writeFileEntry(image, lbn = 9, directory = false, infoLength = 1, dataLbn = 10, data = byteArrayOf(0x42))
        writeFileEntry(image, lbn = 11, directory = false, infoLength = 1, dataLbn = 12, data = byteArrayOf(0x45))
        writeFileEntry(image, lbn = 13, directory = false, infoLength = installWimSize, dataLbn = 14, data = byteArrayOf(0x57, 0x49, 0x4d))
        return image
    }

    private fun writeVolumeRecognitionSequence(image: ByteArray) {
        writeVrs(image, 16, "BEA01")
        writeVrs(image, 17, "NSR02")
        writeVrs(image, 18, "TEA01")
    }

    private fun writeVrs(image: ByteArray, sector: Int, id: String) {
        val offset = sector * UDF_SECTOR_SIZE
        image[offset] = 0
        id.encodeToByteArray().copyInto(image, offset + 1)
        image[offset + 6] = 1
    }

    private fun writeAnchor(image: ByteArray, sector: Int, sequenceSector: Int) {
        val offset = sector * UDF_SECTOR_SIZE
        putLeShort(image, offset, TAG_ANCHOR)
        putLeInt(image, offset + 16, 4 * UDF_SECTOR_SIZE)
        putLeInt(image, offset + 20, sequenceSector)
    }

    private fun writePartitionDescriptor(image: ByteArray, sector: Int, partitionStart: Int) {
        val offset = sector * UDF_SECTOR_SIZE
        putLeShort(image, offset, TAG_PARTITION)
        putLeShort(image, offset + 22, 0)
        putLeInt(image, offset + 188, partitionStart)
        putLeInt(image, offset + 192, 100)
    }

    private fun writeLogicalVolumeDescriptor(image: ByteArray, sector: Int, fileSetLbn: Int) {
        val offset = sector * UDF_SECTOR_SIZE
        putLeShort(image, offset, TAG_LOGICAL_VOLUME)
        putLeInt(image, offset + 212, UDF_SECTOR_SIZE)
        putLongAd(image, offset + 248, UDF_SECTOR_SIZE, fileSetLbn)
    }

    private fun writeFileSetDescriptor(image: ByteArray, lbn: Int, rootLbn: Int) {
        val offset = partitionOffset(lbn)
        putLeShort(image, offset, TAG_FILE_SET)
        putLongAd(image, offset + 400, UDF_SECTOR_SIZE, rootLbn)
    }

    private fun writeFileEntry(
        image: ByteArray,
        lbn: Int,
        directory: Boolean,
        infoLength: Long,
        dataLbn: Int,
        data: ByteArray,
    ) {
        val offset = partitionOffset(lbn)
        putLeShort(image, offset, TAG_FILE_ENTRY)
        image[offset + 27] = if (directory) 4 else 5
        putLeShort(image, offset + 34, 0)
        putLeLong(image, offset + 56, infoLength)
        putLeLong(image, offset + 64, 1)
        putLeInt(image, offset + 168, 0)
        putLeInt(image, offset + 172, 8)
        putLeInt(image, offset + 176, data.size)
        putLeInt(image, offset + 180, dataLbn)
        data.copyInto(image, partitionOffset(dataLbn))
    }

    private fun fid(name: String, entryLbn: Int, entryLength: Int = UDF_SECTOR_SIZE): ByteArray {
        val nameBytes = byteArrayOf(8) + name.encodeToByteArray()
        val bytes = ByteArray(align4(38 + nameBytes.size))
        putLeShort(bytes, 0, TAG_FILE_IDENTIFIER)
        bytes[19] = nameBytes.size.toByte()
        putLongAd(bytes, 20, entryLength, entryLbn)
        putLeShort(bytes, 36, 0)
        nameBytes.copyInto(bytes, 38)
        return bytes
    }

    private fun writeTag(image: ByteArray, sector: Int, tag: Int) {
        putLeShort(image, sector * UDF_SECTOR_SIZE, tag)
    }

    private fun putLongAd(bytes: ByteArray, offset: Int, length: Int, lbn: Int) {
        putLeInt(bytes, offset, length)
        putLeInt(bytes, offset + 4, lbn)
        putLeShort(bytes, offset + 8, 0)
    }

    private fun partitionOffset(lbn: Int): Int =
        (PARTITION_START + lbn) * UDF_SECTOR_SIZE

    private fun align4(value: Int): Int =
        (value + 3) and -4

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

    private fun putLeLong(bytes: ByteArray, offset: Int, value: Long) {
        putLeInt(bytes, offset, value.toInt())
        putLeInt(bytes, offset + 4, (value ushr 32).toInt())
    }

    private companion object {
        const val UDF_SECTOR_SIZE = 2048
        const val PARTITION_START = 400
        const val TAG_ANCHOR = 2
        const val TAG_PARTITION = 5
        const val TAG_LOGICAL_VOLUME = 6
        const val TAG_TERMINATING = 8
        const val TAG_FILE_SET = 256
        const val TAG_FILE_IDENTIFIER = 257
        const val TAG_FILE_ENTRY = 261
    }
}
