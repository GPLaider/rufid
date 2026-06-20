package io.github.rufid.core

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
        val bootLba = partitions.firstOrNull()?.startSector ?: 0L
        val vbr = readBlock(bootLba)
        val bootSector = parseBootSector(bootLba, vbr)
        val evidence = freeDosEvidence(mbr, vbr, bootSector)

        return BootMediaInspection(
            sizeBytes = blockDevice.sizeBytes,
            blockSize = blockDevice.blockSize,
            hasMbrSignature = mbr.hasBootSignature(),
            partitions = partitions,
            bootSector = bootSector,
            freeDosEvidence = evidence,
        )
    }

    private fun readBlock(lba: Long): ByteArray {
        val buffer = ByteArray(blockDevice.blockSize)
        blockDevice.seek(lba * blockDevice.blockSize.toLong())
        blockDevice.read(buffer, 0, buffer.size)
        return buffer
    }

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

        val fat16Type = sector.asciiField(54, 8)
        val fat32Type = sector.asciiField(82, 8)
        val usesFat32Layout = fat32Type.startsWith("FAT")
        val fileSystem = (if (usesFat32Layout) fat32Type else fat16Type).ifBlank { null }
        val volumeLabel = (if (usesFat32Layout) sector.asciiField(71, 11) else sector.asciiField(43, 11))
            .ifBlank { null }

        return BootSectorInfo(
            lba = lba,
            hasSignature = sector.hasBootSignature(),
            oemName = sector.asciiField(3, 8),
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
