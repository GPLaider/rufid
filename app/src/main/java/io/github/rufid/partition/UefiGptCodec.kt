package io.github.rufid.partition

import java.util.UUID
import java.util.zip.CRC32

/**
 * GPT builders for Rufid UEFI:NTFS GPT partition-table mode (protective MBR 0xEE).
 *
 * Type GUIDs (fixed, standard):
 * - Basic Data: EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
 * - ESP:        C12A7328-F81F-11D2-BA4B-00A0C93EC93B
 *
 * Disk/partition unique GUIDs are supplied per layout plan (random in production,
 * injected fixed values in unit tests).
 */
object UefiGptCodec {
    const val SECTOR_SIZE = 512
    const val HEADER_SIZE = 92
    const val PARTITION_ENTRY_SIZE = 128
    const val PARTITION_ENTRY_COUNT = 128
    const val PARTITION_ARRAY_BYTES = PARTITION_ENTRY_SIZE * PARTITION_ENTRY_COUNT
    val PARTITION_ARRAY_SECTORS: Int = PARTITION_ARRAY_BYTES / SECTOR_SIZE

    val TYPE_BASIC_DATA: ByteArray = gptGuidBytes("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    val TYPE_ESP: ByteArray = gptGuidBytes("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")

    /** Fixed GUIDs for deterministic unit tests only. */
    val TEST_DISK_GUID: ByteArray = gptGuidBytes("A1B2C3D4-E5F6-4789-ABCD-EF0123456789")
    val TEST_DATA_UNIQUE_GUID: ByteArray = gptGuidBytes("11111111-2222-3333-4444-555555555555")
    val TEST_ESP_UNIQUE_GUID: ByteArray = gptGuidBytes("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")

    data class GuidSet(
        val diskGuid: ByteArray,
        val dataUniqueGuid: ByteArray,
        val espUniqueGuid: ByteArray,
    ) {
        init {
            require(diskGuid.size == 16 && dataUniqueGuid.size == 16 && espUniqueGuid.size == 16)
        }
    }

    fun randomGuidSet(): GuidSet = GuidSet(
        diskGuid = randomGptGuidBytes(),
        dataUniqueGuid = randomGptGuidBytes(),
        espUniqueGuid = randomGptGuidBytes(),
    )

    fun testGuidSet(): GuidSet = GuidSet(
        diskGuid = TEST_DISK_GUID.copyOf(),
        dataUniqueGuid = TEST_DATA_UNIQUE_GUID.copyOf(),
        espUniqueGuid = TEST_ESP_UNIQUE_GUID.copyOf(),
    )

    data class GptGeometry(
        val totalSectors: Long,
        val primaryHeaderLba: Long,
        val primaryEntriesLba: Long,
        val backupHeaderLba: Long,
        val backupEntriesLba: Long,
        val firstUsableLba: Long,
        val lastUsableLba: Long,
    )

    fun geometry(totalSectors: Long): GptGeometry {
        require(totalSectors > PARTITION_ARRAY_SECTORS + 3L) { "Device too small for GPT." }
        val backupHeaderLba = totalSectors - 1L
        val backupEntriesLba = backupHeaderLba - PARTITION_ARRAY_SECTORS
        val primaryHeaderLba = 1L
        val primaryEntriesLba = 2L
        val firstUsableLba = primaryEntriesLba + PARTITION_ARRAY_SECTORS
        val lastUsableLba = backupEntriesLba - 1L
        require(lastUsableLba >= firstUsableLba) { "No usable LBAs for GPT partitions." }
        return GptGeometry(
            totalSectors = totalSectors,
            primaryHeaderLba = primaryHeaderLba,
            primaryEntriesLba = primaryEntriesLba,
            backupHeaderLba = backupHeaderLba,
            backupEntriesLba = backupEntriesLba,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
        )
    }

    fun partitionArray(
        dataStart: Long,
        dataEndInclusive: Long,
        helperStart: Long,
        helperEndInclusive: Long,
        guids: GuidSet,
    ): ByteArray {
        val bytes = ByteArray(PARTITION_ARRAY_BYTES)
        writeEntry(
            bytes = bytes,
            index = 0,
            type = TYPE_BASIC_DATA,
            unique = guids.dataUniqueGuid,
            startLba = dataStart,
            endLbaInclusive = dataEndInclusive,
            name = "Rufid NTFS Data",
        )
        writeEntry(
            bytes = bytes,
            index = 1,
            type = TYPE_ESP,
            unique = guids.espUniqueGuid,
            startLba = helperStart,
            endLbaInclusive = helperEndInclusive,
            name = "Rufid UEFI NTFS",
        )
        return bytes
    }

    fun header(
        currentLba: Long,
        backupLba: Long,
        firstUsable: Long,
        lastUsable: Long,
        entriesLba: Long,
        partitionArrayCrc: Int,
        diskGuid: ByteArray,
    ): ByteArray {
        val bytes = ByteArray(SECTOR_SIZE)
        bytes[0] = 'E'.code.toByte()
        bytes[1] = 'F'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = ' '.code.toByte()
        bytes[4] = 'P'.code.toByte()
        bytes[5] = 'A'.code.toByte()
        bytes[6] = 'R'.code.toByte()
        bytes[7] = 'T'.code.toByte()
        putLe32(bytes, 8, 0x0001_0000L)
        putLe32(bytes, 12, HEADER_SIZE.toLong())
        putLe32(bytes, 16, 0L)
        putLe64(bytes, 24, currentLba)
        putLe64(bytes, 32, backupLba)
        putLe64(bytes, 40, firstUsable)
        putLe64(bytes, 48, lastUsable)
        diskGuid.copyInto(bytes, 56)
        putLe64(bytes, 72, entriesLba)
        putLe32(bytes, 80, PARTITION_ENTRY_COUNT.toLong())
        putLe32(bytes, 84, PARTITION_ENTRY_SIZE.toLong())
        putLe32(bytes, 88, partitionArrayCrc.toLong() and 0xffff_ffffL)
        val headerCrc = crc32(bytes, 0, HEADER_SIZE)
        putLe32(bytes, 16, headerCrc.toLong() and 0xffff_ffffL)
        return bytes
    }

    fun partitionArrayCrc(array: ByteArray): Int = crc32(array, 0, array.size)

    fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    fun verifyHeaderCrc(sector: ByteArray): Boolean {
        if (sector.size < HEADER_SIZE) return false
        if (!sector.copyOfRange(0, 8).contentEquals("EFI PART".toByteArray(Charsets.US_ASCII))) return false
        val stored = getLe32(sector, 16)
        val copy = sector.copyOf()
        putLe32(copy, 16, 0L)
        return crc32(copy, 0, HEADER_SIZE) == stored
    }

    fun storedPartitionArrayCrc(header: ByteArray): Int = getLe32(header, 88)

    fun entriesLba(header: ByteArray): Long = getLe64(header, 72)

    fun numberOfPartitionEntries(header: ByteArray): Int =
        getLe32(header, 80).coerceIn(0, PARTITION_ENTRY_COUNT)

    fun sizeOfPartitionEntry(header: ByteArray): Int = getLe32(header, 84)

    /** Count non-zero type-GUID entries in a GPT partition array. */
    fun countOccupiedEntries(array: ByteArray, entrySize: Int = PARTITION_ENTRY_SIZE): Int {
        require(entrySize > 0)
        var count = 0
        var offset = 0
        while (offset + 16 <= array.size) {
            var occupied = false
            for (i in 0 until 16) {
                if (array[offset + i] != 0.toByte()) {
                    occupied = true
                    break
                }
            }
            if (occupied) count++
            offset += entrySize
        }
        return count
    }

    /**
     * First occupied entry whose type GUID matches [typeGuid]; returns starting LBA (LE64 at entry+32).
     * Null if none.
     */
    fun firstEntryStartLba(
        array: ByteArray,
        typeGuid: ByteArray,
        entrySize: Int = PARTITION_ENTRY_SIZE,
    ): Long? {
        require(typeGuid.size == 16)
        require(entrySize >= 40)
        var offset = 0
        while (offset + entrySize <= array.size) {
            if (array.copyOfRange(offset, offset + 16).contentEquals(typeGuid)) {
                return getLe64(array, offset + 32)
            }
            offset += entrySize
        }
        return null
    }

    fun verifyPartitionArrayCrcMatchesHeader(header: ByteArray, array: ByteArray): Boolean {
        if (!verifyHeaderCrc(header)) return false
        return partitionArrayCrc(array) == storedPartitionArrayCrc(header)
    }

    private fun writeEntry(
        bytes: ByteArray,
        index: Int,
        type: ByteArray,
        unique: ByteArray,
        startLba: Long,
        endLbaInclusive: Long,
        name: String,
    ) {
        val offset = index * PARTITION_ENTRY_SIZE
        type.copyInto(bytes, offset)
        unique.copyInto(bytes, offset + 16)
        putLe64(bytes, offset + 32, startLba)
        putLe64(bytes, offset + 40, endLbaInclusive)
        putLe64(bytes, offset + 48, 0L)
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        nameBytes.copyOf(minOf(nameBytes.size, 72)).copyInto(bytes, offset + 56)
    }

    fun gptGuidBytes(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        require(hex.length == 32) { "Invalid GUID: $uuid" }
        val raw = ByteArray(16) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return byteArrayOf(
            raw[3], raw[2], raw[1], raw[0],
            raw[5], raw[4],
            raw[7], raw[6],
            raw[8], raw[9], raw[10], raw[11], raw[12], raw[13], raw[14], raw[15],
        )
    }

    fun randomGptGuidBytes(): ByteArray = gptGuidBytes(UUID.randomUUID().toString())

    fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        putLe32(bytes, offset, value and 0xffff_ffffL)
        putLe32(bytes, offset + 4, (value ushr 32) and 0xffff_ffffL)
    }

    fun getLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    fun getLe64(bytes: ByteArray, offset: Int): Long {
        val lo = getLe32(bytes, offset).toLong() and 0xffff_ffffL
        val hi = getLe32(bytes, offset + 4).toLong() and 0xffff_ffffL
        return lo or (hi shl 32)
    }
}
