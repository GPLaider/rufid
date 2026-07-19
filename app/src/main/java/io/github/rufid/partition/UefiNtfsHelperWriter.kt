package io.github.rufid.partition

import io.github.rufid.core.CancellationToken
import io.github.rufid.core.SeekableBlockDevice
import java.io.IOException
import java.security.MessageDigest

/** Explicit partition table mode for UEFI:NTFS helper layout. No silent hybrid. */
enum class UefiNtfsPartitionTableMode {
    /** Only MBR: 0x07 data + 0xEF helper. No GPT structures. */
    Mbr,

    /** Protective MBR 0xEE + primary/backup GPT (Basic Data + ESP). */
    Gpt,
}

data class UefiNtfsLayout(
    val dataStartSector: Long,
    val dataSectorCount: Long,
    val helperStartSector: Long,
    val helperSectorCount: Long,
    val sectorSize: Int,
    val mode: UefiNtfsPartitionTableMode,
    val gptFirstUsableLba: Long = 0L,
    val gptLastUsableLba: Long = 0L,
)

fun interface UefiNtfsPayloadSource {
    fun load(): ByteArray?
}

class UefiNtfsRuntimeWriter(
    private val blockDevice: SeekableBlockDevice,
    private val mode: UefiNtfsPartitionTableMode = UefiNtfsPartitionTableMode.Mbr,
    private val payloadSource: UefiNtfsPayloadSource,
) {
    fun write(cancellationToken: CancellationToken = CancellationToken.None): UefiNtfsLayout {
        val helperImage = checkNotNull(payloadSource.load()) {
            "UEFI:NTFS helper payload is not packaged."
        }
        return UefiNtfsHelperWriter(blockDevice).write(
            helperImage = helperImage,
            mode = mode,
            cancellationToken = cancellationToken,
        )
    }
}

/**
 * Writes UEFI:NTFS **layout only** (partition tables + helper image).
 * Does **not** create an NTFS filesystem or copy Windows files.
 */
class UefiNtfsHelperWriter(
    private val blockDevice: SeekableBlockDevice,
) {
    fun plan(
        helperImage: ByteArray,
        mode: UefiNtfsPartitionTableMode,
        guids: UefiGptCodec.GuidSet = UefiGptCodec.randomGuidSet(),
    ): UefiNtfsLayout = planAndValidate(helperImage, mode, guids).layout

    fun write(
        helperImage: ByteArray,
        mode: UefiNtfsPartitionTableMode = UefiNtfsPartitionTableMode.Mbr,
        cancellationToken: CancellationToken = CancellationToken.None,
        guids: UefiGptCodec.GuidSet = UefiGptCodec.randomGuidSet(),
    ): UefiNtfsLayout {
        val planned = planAndValidate(helperImage, mode, guids)
        return when (mode) {
            UefiNtfsPartitionTableMode.Mbr -> writeMbrMode(planned, helperImage, cancellationToken)
            UefiNtfsPartitionTableMode.Gpt -> writeGptMode(planned, helperImage, guids, cancellationToken)
        }
    }

    private data class Planned(
        val layout: UefiNtfsLayout,
        val gpt: UefiGptCodec.GptGeometry?,
    )

    private fun planAndValidate(
        helperImage: ByteArray,
        mode: UefiNtfsPartitionTableMode,
        guids: UefiGptCodec.GuidSet,
    ): Planned {
        val sectorSize = blockDevice.blockSize
        require(sectorSize == MBR_SECTOR_SIZE) { "UEFI:NTFS requires 512-byte logical sectors." }
        require(helperImage.size >= MBR_SECTOR_SIZE && helperImage.size % sectorSize == 0) {
            "UEFI:NTFS helper image must contain whole 512-byte sectors."
        }
        require(helperImage[510] == 0x55.toByte() && helperImage[511] == 0xAA.toByte()) {
            "UEFI:NTFS helper image has no FAT boot signature."
        }
        require(littleEndianShort(helperImage, 11) == sectorSize) {
            "UEFI:NTFS helper image sector size does not match the USB device."
        }

        val totalSectors = blockDevice.sizeBytes / sectorSize
        require(totalSectors <= UINT32_MAX) { "UEFI:NTFS layout exceeds the 32-bit sector limit." }
        val helperSectors = helperImage.size.toLong() / sectorSize

        return when (mode) {
            UefiNtfsPartitionTableMode.Mbr -> {
                val helperStart = totalSectors - helperSectors
                require(helperStart > DATA_START_SECTOR) { "USB device is too small for UEFI:NTFS partitions." }
                val dataSectors = helperStart - DATA_START_SECTOR
                require(dataSectors in 1..UINT32_MAX && helperSectors in 1..UINT32_MAX) {
                    "Partition sizes exceed MBR uint32 field limits."
                }
                Planned(
                    layout = UefiNtfsLayout(
                        dataStartSector = DATA_START_SECTOR,
                        dataSectorCount = dataSectors,
                        helperStartSector = helperStart,
                        helperSectorCount = helperSectors,
                        sectorSize = sectorSize,
                        mode = UefiNtfsPartitionTableMode.Mbr,
                    ),
                    gpt = null,
                )
            }
            UefiNtfsPartitionTableMode.Gpt -> {
                val gpt = UefiGptCodec.geometry(totalSectors)
                val helperStart = gpt.lastUsableLba - helperSectors + 1L
                require(helperStart > DATA_START_SECTOR) { "USB device is too small for UEFI:NTFS partitions." }
                require(DATA_START_SECTOR >= gpt.firstUsableLba) {
                    "Data partition start is before GPT first usable LBA."
                }
                require(helperStart + helperSectors - 1L <= gpt.lastUsableLba) {
                    "Helper partition exceeds GPT last usable LBA."
                }
                val dataSectors = helperStart - DATA_START_SECTOR
                require(dataSectors >= 1L && helperSectors >= 1L) { "Invalid GPT partition sizes." }
                // guids validated by use in write; required parameter ensures plan generates them
                require(guids.diskGuid.size == 16)
                Planned(
                    layout = UefiNtfsLayout(
                        dataStartSector = DATA_START_SECTOR,
                        dataSectorCount = dataSectors,
                        helperStartSector = helperStart,
                        helperSectorCount = helperSectors,
                        sectorSize = sectorSize,
                        mode = UefiNtfsPartitionTableMode.Gpt,
                        gptFirstUsableLba = gpt.firstUsableLba,
                        gptLastUsableLba = gpt.lastUsableLba,
                    ),
                    gpt = gpt,
                )
            }
        }
    }

    private fun writeMbrMode(
        planned: Planned,
        helperImage: ByteArray,
        cancellationToken: CancellationToken,
    ): UefiNtfsLayout {
        val layout = planned.layout
        val mbr = createLegacyMbr(layout)

        cancellationToken.throwIfCancelled()
        writeFully(layout.helperStartSector * layout.sectorSize.toLong(), helperImage)
        cancellationToken.throwIfCancelled()
        writeFully(0L, mbr)
        blockDevice.flush()

        cancellationToken.throwIfCancelled()
        verifyMbrMode(layout, mbr, helperImage)
        return layout
    }

    private fun writeGptMode(
        planned: Planned,
        helperImage: ByteArray,
        guids: UefiGptCodec.GuidSet,
        cancellationToken: CancellationToken,
    ): UefiNtfsLayout {
        val layout = planned.layout
        val gpt = checkNotNull(planned.gpt)
        val partitionArray = UefiGptCodec.partitionArray(
            dataStart = layout.dataStartSector,
            dataEndInclusive = layout.dataStartSector + layout.dataSectorCount - 1L,
            helperStart = layout.helperStartSector,
            helperEndInclusive = layout.helperStartSector + layout.helperSectorCount - 1L,
            guids = guids,
        )
        val arrayCrc = UefiGptCodec.partitionArrayCrc(partitionArray)
        val primaryHeader = UefiGptCodec.header(
            currentLba = gpt.primaryHeaderLba,
            backupLba = gpt.backupHeaderLba,
            firstUsable = gpt.firstUsableLba,
            lastUsable = gpt.lastUsableLba,
            entriesLba = gpt.primaryEntriesLba,
            partitionArrayCrc = arrayCrc,
            diskGuid = guids.diskGuid,
        )
        val backupHeader = UefiGptCodec.header(
            currentLba = gpt.backupHeaderLba,
            backupLba = gpt.primaryHeaderLba,
            firstUsable = gpt.firstUsableLba,
            lastUsable = gpt.lastUsableLba,
            entriesLba = gpt.backupEntriesLba,
            partitionArrayCrc = arrayCrc,
            diskGuid = guids.diskGuid,
        )
        val protectiveMbr = createProtectiveMbr(gpt.totalSectors)

        cancellationToken.throwIfCancelled()
        writeFully(layout.helperStartSector * layout.sectorSize.toLong(), helperImage)
        cancellationToken.throwIfCancelled()
        writeFully(gpt.primaryEntriesLba * layout.sectorSize.toLong(), partitionArray)
        writeFully(gpt.primaryHeaderLba * layout.sectorSize.toLong(), primaryHeader)
        cancellationToken.throwIfCancelled()
        writeFully(gpt.backupEntriesLba * layout.sectorSize.toLong(), partitionArray)
        writeFully(gpt.backupHeaderLba * layout.sectorSize.toLong(), backupHeader)
        cancellationToken.throwIfCancelled()
        writeFully(0L, protectiveMbr)
        blockDevice.flush()

        cancellationToken.throwIfCancelled()
        verifyGptMode(layout, gpt, protectiveMbr, helperImage)
        return layout
    }

    private fun createLegacyMbr(layout: UefiNtfsLayout): ByteArray = ByteArray(MBR_SECTOR_SIZE).also { bytes ->
        writePartition(bytes, 0, NTFS_PARTITION_TYPE, layout.dataStartSector, layout.dataSectorCount)
        writePartition(bytes, 1, EFI_SYSTEM_PARTITION_TYPE, layout.helperStartSector, layout.helperSectorCount)
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
    }

    private fun createProtectiveMbr(totalSectors: Long): ByteArray = ByteArray(MBR_SECTOR_SIZE).also { bytes ->
        // Protective MBR: one partition type 0xEE covering the disk from LBA 1.
        val count = (totalSectors - 1L).coerceAtMost(UINT32_MAX)
        writePartition(bytes, 0, PROTECTIVE_GPT_TYPE, 1L, count)
        bytes[510] = 0x55
        bytes[511] = 0xAA.toByte()
    }

    private fun writePartition(bytes: ByteArray, index: Int, type: Int, startSector: Long, sectorCount: Long) {
        require(startSector in 1..UINT32_MAX && sectorCount in 1..UINT32_MAX)
        val offset = MBR_PARTITION_TABLE_OFFSET + index * MBR_PARTITION_ENTRY_SIZE
        bytes[offset + 1] = 0xFF.toByte()
        bytes[offset + 2] = 0xFF.toByte()
        bytes[offset + 3] = 0xFF.toByte()
        bytes[offset + 4] = type.toByte()
        bytes[offset + 5] = 0xFF.toByte()
        bytes[offset + 6] = 0xFF.toByte()
        bytes[offset + 7] = 0xFF.toByte()
        putLittleEndianInt(bytes, offset + 8, startSector)
        putLittleEndianInt(bytes, offset + 12, sectorCount)
    }

    private fun verifyMbrMode(layout: UefiNtfsLayout, expectedMbr: ByteArray, expectedHelper: ByteArray) {
        val mbr = readFully(0L, MBR_SECTOR_SIZE)
        if (!mbr.contentEquals(expectedMbr)) {
            throw IOException("UEFI:NTFS MBR readback mismatch.")
        }
        if ((mbr[446 + 4].toInt() and 0xff) != NTFS_PARTITION_TYPE ||
            (mbr[462 + 4].toInt() and 0xff) != EFI_SYSTEM_PARTITION_TYPE
        ) {
            throw IOException("UEFI:NTFS MBR partition types mismatch after write.")
        }
        // MBR mode must not place a GPT header at LBA 1.
        val lba1 = readFully(layout.sectorSize.toLong(), MBR_SECTOR_SIZE)
        if (lba1.copyOfRange(0, 8).contentEquals("EFI PART".toByteArray(Charsets.US_ASCII))) {
            throw IOException("UEFI:NTFS MBR mode must not write GPT structures.")
        }
        val helper = readFully(layout.helperStartSector * layout.sectorSize.toLong(), expectedHelper.size)
        if (!helper.contentEquals(expectedHelper)) {
            throw IOException("UEFI:NTFS helper payload readback mismatch.")
        }
    }

    private fun verifyGptMode(
        layout: UefiNtfsLayout,
        gpt: UefiGptCodec.GptGeometry,
        expectedProtectiveMbr: ByteArray,
        expectedHelper: ByteArray,
    ) {
        val mbr = readFully(0L, MBR_SECTOR_SIZE)
        if (!mbr.contentEquals(expectedProtectiveMbr)) {
            throw IOException("UEFI:NTFS protective MBR readback mismatch.")
        }
        if ((mbr[446 + 4].toInt() and 0xff) != PROTECTIVE_GPT_TYPE) {
            throw IOException("UEFI:NTFS protective MBR must use type 0xEE.")
        }

        val primaryHeader = readFully(gpt.primaryHeaderLba * layout.sectorSize.toLong(), MBR_SECTOR_SIZE)
        if (!UefiGptCodec.verifyHeaderCrc(primaryHeader)) {
            throw IOException("UEFI:NTFS primary GPT header CRC invalid.")
        }
        val primaryEntries = readFully(
            gpt.primaryEntriesLba * layout.sectorSize.toLong(),
            UefiGptCodec.PARTITION_ARRAY_BYTES,
        )
        if (!UefiGptCodec.verifyPartitionArrayCrcMatchesHeader(primaryHeader, primaryEntries)) {
            throw IOException("UEFI:NTFS primary GPT partition-array CRC does not match header.")
        }
        if (UefiGptCodec.countOccupiedEntries(primaryEntries) != 2) {
            throw IOException("UEFI:NTFS GPT must contain exactly 2 occupied partition entries.")
        }

        val backupHeader = readFully(gpt.backupHeaderLba * layout.sectorSize.toLong(), MBR_SECTOR_SIZE)
        if (!UefiGptCodec.verifyHeaderCrc(backupHeader)) {
            throw IOException("UEFI:NTFS backup GPT header CRC invalid.")
        }
        val backupEntries = readFully(
            gpt.backupEntriesLba * layout.sectorSize.toLong(),
            UefiGptCodec.PARTITION_ARRAY_BYTES,
        )
        if (!UefiGptCodec.verifyPartitionArrayCrcMatchesHeader(backupHeader, backupEntries)) {
            throw IOException("UEFI:NTFS backup GPT partition-array CRC does not match header.")
        }
        if (!primaryEntries.contentEquals(backupEntries)) {
            throw IOException("UEFI:NTFS primary/backup GPT entries differ.")
        }

        val helper = readFully(layout.helperStartSector * layout.sectorSize.toLong(), expectedHelper.size)
        if (!helper.contentEquals(expectedHelper)) {
            throw IOException("UEFI:NTFS helper payload readback mismatch.")
        }
    }

    private fun writeFully(byteOffset: Long, data: ByteArray) {
        blockDevice.seek(byteOffset)
        blockDevice.write(data, 0, data.size)
    }

    private fun readFully(byteOffset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        blockDevice.seek(byteOffset)
        var done = 0
        while (done < length) {
            val read = blockDevice.read(buffer, done, length - done)
            if (read <= 0) {
                throw IOException("Short read at offset $byteOffset+$done (wanted $length, got $done).")
            }
            done += read
        }
        return buffer
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun putLittleEndianInt(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private companion object {
        const val MBR_SECTOR_SIZE = 512
        const val MBR_PARTITION_TABLE_OFFSET = 446
        const val MBR_PARTITION_ENTRY_SIZE = 16
        const val NTFS_PARTITION_TYPE = 0x07
        const val EFI_SYSTEM_PARTITION_TYPE = 0xEF
        const val PROTECTIVE_GPT_TYPE = 0xEE
        const val DATA_START_SECTOR = 2048L
        const val UINT32_MAX = 0xffff_ffffL
    }
}

enum class UefiArchitecture {
    X64,
    Ia32,
    Arm64,
    Arm32,
}

class UefiNtfsSecureBootVerifier(
    private val allowedPayloads: Map<String, Set<UefiArchitecture>> = PINNED_SIGNED_PAYLOADS,
) {
    fun matchesPinnedSignedPayload(payload: ByteArray, architecture: UefiArchitecture): Boolean {
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
        return architecture in allowedPayloads[hash].orEmpty()
    }

    @Deprecated(
        message = "A pinned payload hash does not prove that the completed USB boots with Secure Boot enabled.",
        replaceWith = ReplaceWith("matchesPinnedSignedPayload(payload, architecture)"),
    )
    fun isCapable(payload: ByteArray, architecture: UefiArchitecture): Boolean {
        return matchesPinnedSignedPayload(payload, architecture)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val PINNED_SIGNED_PAYLOADS = mapOf(
            "72683fa1250eeea772d3399277b434d4e55ba8dd0dc926e52d817e701fc2eb9e" to
                setOf(UefiArchitecture.X64, UefiArchitecture.Ia32, UefiArchitecture.Arm64),
        )
    }
}
