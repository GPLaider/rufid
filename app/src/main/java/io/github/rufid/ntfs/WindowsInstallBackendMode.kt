package io.github.rufid.ntfs

import io.github.rufid.partition.UefiNtfsPartitionTableMode

/**
 * Explicit backend target for Windows ISO write.
 * UI redesign is stage 6; backend is callable end-to-end now.
 */
enum class WindowsInstallBackendMode {
    /** Existing FAT32 extraction path; large install.wim may split. */
    Fat32Extraction,

    /** NTFS data volume + UEFI:NTFS helper, MBR 0x07+0xEF. install.wim unsplit. */
    NtfsUefiMbr,

    /** NTFS data volume + UEFI:NTFS helper, protective GPT. install.wim unsplit. */
    NtfsUefiGpt,
    ;

    val usesNtfs: Boolean
        get() = this == NtfsUefiMbr || this == NtfsUefiGpt

    val partitionTableMode: UefiNtfsPartitionTableMode?
        get() = when (this) {
            Fat32Extraction -> null
            NtfsUefiMbr -> UefiNtfsPartitionTableMode.Mbr
            NtfsUefiGpt -> UefiNtfsPartitionTableMode.Gpt
        }

    /** NTFS keeps install.wim whole; FAT32 may split. */
    val keepInstallWimUnsplit: Boolean
        get() = usesNtfs
}
