package io.github.rufid.core

internal data class UdfVolumeDescriptors(
    val partition: UdfPartition?,
    val fileSetDescriptor: UdfLongAd,
)

internal data class UdfFileSetDescriptor(
    val rootIcb: UdfLongAd,
)

internal data class UdfPartition(
    val number: Int,
    val startSector: Long,
    val length: Long,
) {
    fun byteOffset(logicalBlock: Long): Long = (startSector + logicalBlock) * UDF_SECTOR_SIZE
}

internal data class UdfExtent(
    val length: Int,
    val location: Int,
)

internal data class UdfLongAd(
    val length: Int,
    val logicalBlock: Long,
    val partitionReference: Int,
)

internal data class UdfFileExtent(
    val byteOffset: Long,
    val length: Long,
)

internal data class UdfFileEntry(
    val fileType: Int,
    val informationLength: Long,
    val extents: List<UdfFileExtent>,
    val embedded: ByteArray?,
) {
    val isDirectory: Boolean
        get() = fileType == UDF_FILE_TYPE_DIRECTORY
}

internal fun ByteArray.readUdfExtent(offset: Int): UdfExtent =
    UdfExtent(length = readLeUInt(offset).toInt(), location = readLeUInt(offset + 4).toInt())

internal fun ByteArray.readUdfLongAd(offset: Int): UdfLongAd =
    UdfLongAd(
        length = (readLeUInt(offset) and UDF_EXTENT_LENGTH_MASK).toInt(),
        logicalBlock = readLeUInt(offset + 4),
        partitionReference = readLeUShort(offset + 8),
    )

internal fun ByteArray.readLeUShort(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.readLeUInt(offset: Int): Long =
    (this[offset].toInt() and 0xff).toLong() or
        ((this[offset + 1].toInt() and 0xff).toLong() shl 8) or
        ((this[offset + 2].toInt() and 0xff).toLong() shl 16) or
        ((this[offset + 3].toInt() and 0xff).toLong() shl 24)

internal fun ByteArray.readLeULong(offset: Int): Long =
    readLeUInt(offset) or (readLeUInt(offset + 4) shl 32)

internal fun IntRange.indicesStep(step: Int): List<Int> =
    filter { (it - first) % step == 0 && it + step <= last + 1 }

internal fun align4(value: Int): Int =
    (value + 3) and -4

internal const val UDF_SECTOR_SIZE = 2048
internal const val UDF_MAX_DIRECTORY_BYTES = 16 * 1024 * 1024
internal const val UDF_TAG_ANCHOR = 2
internal const val UDF_TAG_PARTITION = 5
internal const val UDF_TAG_LOGICAL_VOLUME = 6
internal const val UDF_TAG_TERMINATING = 8
internal const val UDF_TAG_FILE_SET = 256
internal const val UDF_TAG_FILE_IDENTIFIER = 257
internal const val UDF_TAG_FILE_ENTRY = 261
internal const val UDF_TAG_EXTENDED_FILE_ENTRY = 266
internal const val UDF_FILE_TYPE_DIRECTORY = 4
internal const val UDF_FILE_CHARACTERISTIC_DELETED = 0x04
internal const val UDF_FILE_CHARACTERISTIC_PARENT = 0x08
internal const val UDF_ALLOCATION_SHORT = 0
internal const val UDF_ALLOCATION_LONG = 1
internal const val UDF_ALLOCATION_EMBEDDED = 3
internal const val UDF_EXTENT_LENGTH_MASK = 0x3fffffffL
