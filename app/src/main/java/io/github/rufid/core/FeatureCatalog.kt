package io.github.rufid.core

enum class FeatureStatus {
    ImplementedFoundation,
    EnginePlanned,
    LicenseReviewRequired,
}

data class RufidFeature(
    val title: String,
    val detail: String,
    val status: FeatureStatus,
)

object FeatureCatalog {
    val supportedWorkflows: List<RufidFeature> = listOf(
        RufidFeature(
            "Bootable image writer",
            "ISO/IMG/DMG/raw image path with direct USB block writing, cancellation, and verification.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "USB backup and restore",
            "Whole-device raw read/write foundation for backup and restore workflows.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "USB benchmark",
            "Read benchmark foundation with cancellation; destructive write benchmark needs guarded confirmation.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "USB device diagnostics",
            "USB host, SCSI capacity, sense errors, and read-only capacity samples are exposed by the device layer.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "Format USB",
            "Guarded USB recovery/reinitialize can quick-wipe metadata and format one FAT32 or exFAT MBR volume.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "Partition management",
            "MBR/GPT planning surface is present; recovery writes guarded MBR FAT32 and exFAT layouts.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "Windows ISO helper",
            "WIM split and FAT32 4GB flow are modeled; wimlib integration requires source distribution.",
            FeatureStatus.LicenseReviewRequired,
        ),
        RufidFeature(
            "FreeDOS / MS-DOS boot",
            "Workflow is tracked; bundled DOS payloads require source, notices, and replacement policy.",
            FeatureStatus.LicenseReviewRequired,
        ),
        RufidFeature(
            "UEFI:NTFS helper",
            "Workflow is tracked; boot payloads require exact source, notices, and reproducibility plan.",
            FeatureStatus.LicenseReviewRequired,
        ),
        RufidFeature(
            "Archive extraction",
            "ZIP extraction to a selected SAF folder is implemented; 7z and WIM need reviewed dependencies.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "Direct download to USB",
            "URL stream writes directly to USB through the same raw writer path.",
            FeatureStatus.ImplementedFoundation,
        ),
        RufidFeature(
            "Bad/fake drive detection",
            "Read-only capacity sampling is present; destructive fake-capacity probe must be opt-in.",
            FeatureStatus.EnginePlanned,
        ),
    )
}
