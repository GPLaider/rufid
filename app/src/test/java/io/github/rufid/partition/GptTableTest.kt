package io.github.rufid.partition

import org.junit.Assert.assertEquals
import org.junit.Test

class GptTableTest {
    @Test
    fun usesCallerProvidedPartitionName() {
        val plan = PartitionPlan(
            tableType = PartitionTableType.Gpt,
            fileSystemType = FileSystemType.ExFat,
            bootPayloadKind = BootPayloadKind.None,
            startSector = 2048,
            sectorCount = 262_144,
            sectorSize = 512,
        )

        val (_, entries) = GptTable(plan, partitionName = "SANDISK 32G").primaryHeaderAndEntries()

        assertEquals("SANDISK 32G", entries.decodeGptPartitionName())
    }

    private fun ByteArray.decodeGptPartitionName(): String {
        val nameOffset = 56
        val nameLength = 72
        return copyOfRange(nameOffset, nameOffset + nameLength)
            .toString(Charsets.UTF_16LE)
            .trimEnd('\u0000')
    }
}
