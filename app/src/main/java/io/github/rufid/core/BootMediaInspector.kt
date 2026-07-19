package io.github.rufid.core

import io.github.rufid.partition.UefiGptCodec
import java.io.IOException
import java.nio.charset.StandardCharsets

data class BootPartitionEntry(
    val index: Int,
    val bootable: Boolean,
    val typeHex: String,
    val startSector: Long,
    val sectorCount: Long,
)

data class BootSectorInfo(
    val lba: Long,
    val hasSignature: Boolean,
    val oemName: String,
    val volumeLabel: String?,
    val fileSystem: String?,
)

data class BootMediaInspection(
    val sizeBytes: Long,
    val blockSize: Int,
    val hasMbrSignature: Boolean,
    val partitions: List<BootPartitionEntry>,
    val bootSector: BootSectorInfo,
    val freeDosEvidence: List<String>,
    val hasGptSignature: Boolean = false,
    val gptPartitionCount: Int = 0,
    val looksLikeNtfsVolume: Boolean = false,
) {
    val looksLikeFreeDos: Boolean
        get() = freeDosEvidence.isNotEmpty()
}

class BootMediaInspector(
    private val blockDevice: SeekableBlockDevice,
) {
    fun inspect(): BootMediaInspection {
        val mbr = readBlock(0)
        val partitions = parsePartitions(mbr)
        val protectiveGpt = partitions.any { it.typeHex.equals("0xEE", ignoreCase = true) }

        // Protective GPT: strict GPT first, then VBR at Basic Data start LBA. Never treat LBA 1 as VBR.
        // Plain MBR: first non-empty partition start (unchanged).
        val bootLba: Long
        val gptInfo: GptInspect
        if (protectiveGpt) {
            gptInfo = inspectGptStrict()
            bootLba = gptInfo.basicDataStartLba
                ?: throw IOException("Protective GPT has no Basic Data partition entry.")
        } else {
            gptInfo = GptInspect(hasSignature = false, occupiedEntries = 0, basicDataStartLba = null)
            bootLba = partitions.firstOrNull()?.startSector ?: 0L
        }

        val vbr = readBlock(bootLba)
        val bootSector = parseBootSector(bootLba, vbr)
        val evidence = freeDosEvidence(mbr, vbr, bootSector)

        val looksNtfs = bootSector.oemName.uppercase().startsWith("NTFS") ||
            bootSector.fileSystem?.uppercase()?.startsWith("NTFS") == true

        return BootMediaInspection(
            sizeBytes = blockDevice.sizeBytes,
            blockSize = blockDevice.blockSize,
            hasMbrSignature = mbr.hasBootSignature(),
            partitions = partitions,
            bootSector = bootSector,
            freeDosEvidence = evidence,
            hasGptSignature = gptInfo.hasSignature,
            gptPartitionCount = gptInfo.occupiedEntries,
            looksLikeNtfsVolume = looksNtfs,
        )
    }

    private data class GptInspect(
        val hasSignature: Boolean,
        val occupiedEntries: Int,
        val basicDataStartLba: Long?,
    )

    private fun inspectGptStrict(): GptInspect {
        val header = readBlock(1)
        if (header.decodeAscii(0, 8) != "EFI PART") {
            throw IOException("Protective MBR indicates GPT but LBA 1 is not a GPT header.")
        }
        if (!UefiGptCodec.verifyHeaderCrc(header)) {
            throw IOException("GPT primary header CRC invalid.")
        }
        val entriesLba = UefiGptCodec.entriesLba(header)
        val entryCount = UefiGptCodec.numberOfPartitionEntries(header)
        val entrySize = UefiGptCodec.sizeOfPartitionEntry(header)
        require(entrySize == UefiGptCodec.PARTITION_ENTRY_SIZE) {
            "Unsupported GPT partition entry size: $entrySize"
        }
        val arrayBytes = entryCount * entrySize
        val entries = readFully(entriesLba * blockDevice.blockSize.toLong(), arrayBytes)
        if (!UefiGptCodec.verifyPartitionArrayCrcMatchesHeader(header, entries)) {
            throw IOException("GPT primary partition-array CRC does not match header.")
        }
        val occupied = UefiGptCodec.countOccupiedEntries(entries, entrySize)
        val basicDataStart = UefiGptCodec.firstEntryStartLba(entries, UefiGptCodec.TYPE_BASIC_DATA, entrySize)
        return GptInspect(
            hasSignature = true,
            occupiedEntries = occupied,
            basicDataStartLba = basicDataStart,
        )
    }

    private fun readBlock(lba: Long): ByteArray {
        return readFully(lba * blockDevice.blockSize.toLong(), blockDevice.blockSize)
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

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        String(copyOfRange(offset, offset + length), StandardCharsets.US_ASCII)

    private fun parsePartitions(mbr: ByteArray): List<BootPartitionEntry> =
        (0 until 4).mapNotNull { slot ->
            val offset = 446 + slot * 16
            val type = mbr.u8(offset + 4)
            val startSector = mbr.u32Le(offset + 8)
            val sectorCount = mbr.u32Le(offset + 12)
            if (type == 0 || sectorCount == 0L) {
                null
            } else {
                BootPartitionEntry(
                    index = slot + 1,
                    bootable = mbr.u8(offset) == 0x80,
                    typeHex = "0x${type.toString(16).uppercase().padStart(2, '0')}",
                    startSector = startSector,
                    sectorCount = sectorCount,
                )
            }
        }

    private fun parseBootSector(lba: Long, sector: ByteArray): BootSectorInfo {
        if (sector.asciiField(3, 8) == "EXFAT") {
            return BootSectorInfo(
                lba = lba,
                hasSignature = sector.hasBootSignature(),
                oemName = "EXFAT",
                volumeLabel = exFatVolumeLabel(lba, sector),
                fileSystem = "exFAT",
            )
        }

        val oem = sector.asciiField(3, 8)
        if (oem.uppercase().startsWith("NTFS")) {
            // Volume label lives in the $Volume attribute, not a fixed VBR offset.
            return BootSectorInfo(
                lba = lba,
                hasSignature = sector.hasBootSignature(),
                oemName = oem,
                volumeLabel = null,
                fileSystem = "NTFS",
            )
        }

        val fat16Type = sector.asciiField(54, 8)
        val fat32Type = sector.asciiField(82, 8)
        val usesFat32Layout = fat32Type.startsWith("FAT")
        val fileSystem = (if (usesFat32Layout) fat32Type else fat16Type).ifBlank { null }
        val volumeLabel = (if (usesFat32Layout) sector.asciiField(71, 11) else sector.asciiField(43, 11))
            .ifBlank { null }

        return BootSectorInfo(
            lba = lba,
            hasSignature = sector.hasBootSignature(),
            oemName = oem,
            volumeLabel = volumeLabel,
            fileSystem = fileSystem,
        )
    }

    private fun exFatVolumeLabel(bootLba: Long, sector: ByteArray): String? {
        val bytesPerSector = 1 shl sector.u8(108)
        if (bytesPerSector != blockDevice.blockSize) return null
        val sectorsPerCluster = 1 shl sector.u8(109)
        val clusterHeapOffset = sector.u32Le(88)
        val rootCluster = sector.u32Le(96)
        if (rootCluster < 2) return null

        val rootLba = bootLba + clusterHeapOffset + ((rootCluster - 2L) * sectorsPerCluster.toLong())
        val root = readBlock(rootLba)
        for (offset in 0 until root.size step 32) {
            when (root.u8(offset)) {
                0x00 -> return null
                0x83 -> {
                    val length = root.u8(offset + 1).coerceIn(0, 11)
                    val labelBytes = root.copyOfRange(offset + 2, offset + 2 + length * 2)
                    return String(labelBytes, StandardCharsets.UTF_16LE).trim().ifBlank { null }
                }
            }
        }
        return null
    }

    private fun freeDosEvidence(mbr: ByteArray, vbr: ByteArray, bootSector: BootSectorInfo): List<String> {
        val text = (mbr + vbr).printableUppercase()
        val evidence = mutableListOf<String>()
        if (bootSector.oemName.uppercase().startsWith("FRDOS")) {
            evidence += "OEM name ${bootSector.oemName}"
        }
        val label = bootSector.volumeLabel.orEmpty().uppercase()
        if (label.contains("FD14") || label.contains("FREEDOS")) {
            evidence += "Volume label ${bootSector.volumeLabel}"
        }
        if (text.contains("FREEDOS")) {
            evidence += "Sector text contains FreeDOS"
        }
        if (text.contains("FD14")) {
            evidence += "Sector text contains FD14"
        }
        return evidence.distinct()
    }

    private fun ByteArray.hasBootSignature(): Boolean =
        size >= 512 && u8(510) == 0x55 && u8(511) == 0xaa

    private fun ByteArray.u8(offset: Int): Int =
        this[offset].toInt() and 0xff

    private fun ByteArray.u32Le(offset: Int): Long =
        (u8(offset).toLong()) or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)

    private fun ByteArray.asciiField(offset: Int, length: Int): String =
        String(copyOfRange(offset, offset + length), StandardCharsets.US_ASCII)
            .replace('\u0000', ' ')
            .trim()

    private fun ByteArray.printableUppercase(): String =
        map { byte ->
            val value = byte.toInt() and 0xff
            if (value in 32..126) value.toChar() else ' '
        }.joinToString(separator = "").uppercase()
}
