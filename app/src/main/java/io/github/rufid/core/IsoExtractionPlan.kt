package io.github.rufid.core

import io.github.rufid.windows.WindowsIsoPlan

enum class IsoWriteMode {
    RawImage,
    WindowsFat32,
    WindowsNtfsMbr,
    WindowsNtfsGpt,
    ;

    val displayName: String
        get() = when (this) {
            RawImage -> "Raw / DD"
            WindowsFat32 -> "Windows FAT32"
            WindowsNtfsMbr -> "Windows NTFS MBR"
            WindowsNtfsGpt -> "Windows NTFS GPT"
        }

    val requiresWindowsInstaller: Boolean
        get() = this != RawImage

    val verificationKind: VerificationKind
        get() = when (this) {
            RawImage -> VerificationKind.RawBytes
            WindowsFat32,
            WindowsNtfsMbr,
            WindowsNtfsGpt,
            -> VerificationKind.WindowsStructure
        }
}

enum class VerificationKind {
    RawBytes,
    WindowsStructure,
}

enum class IsoExtractionSupport {
    WindowsInstaller,
    Unsupported,
}

data class IsoFileEntry(
    val path: String,
    val size: Long,
)

data class IsoExtractionPlan(
    val imageName: String,
    val support: IsoExtractionSupport,
    val recommendedMode: IsoWriteMode,
    val targetLayout: String,
    val requiresWimSplit: Boolean,
    val recommendation: String,
    val installImagePath: String? = null,
    val installImageSize: Long? = null,
) {
    fun summaryLines(): List<String> {
        val lines = mutableListOf<String>()
        when (support) {
            IsoExtractionSupport.WindowsInstaller -> {
                lines += "Windows installer ISO detected"
                lines += "Mode: ISO extraction"
                lines += "Target layout: $targetLayout"
                lines += "install.wim split required: ${if (requiresWimSplit) "yes" else "no"}"
                if (installImagePath != null && installImageSize != null) {
                    lines += "$installImagePath: ${ByteFormatter.format(installImageSize)}"
                }
            }
            IsoExtractionSupport.Unsupported -> {
                lines += "ISO extraction not supported for this image yet"
                lines += "Mode: Raw image"
                lines += "Target layout: $targetLayout"
            }
        }
        lines += "Recommendation: $recommendation"
        return lines
    }
}

object IsoExtractionPlanner {
    fun plan(imageName: String, entries: List<IsoFileEntry>): IsoExtractionPlan {
        val normalizedEntries = entries.associateBy { normalizeIsoPath(it.path) }
        val installImage = normalizedEntries["sources/install.wim"]
            ?: normalizedEntries["sources/install.esd"]
        val isWindowsInstaller = normalizedEntries.containsKey("bootmgr") &&
            normalizedEntries.containsKey("efi/boot/bootx64.efi") &&
            installImage != null

        return if (isWindowsInstaller) {
            val requiresWimSplit = installImage?.let {
                it.path.endsWith("install.wim", ignoreCase = true) &&
                    it.size > WindowsIsoPlan.FAT32_MAX_FILE_SIZE
            } ?: false
            IsoExtractionPlan(
                imageName = imageName,
                support = IsoExtractionSupport.WindowsInstaller,
                recommendedMode = IsoWriteMode.WindowsFat32,
                targetLayout = "FAT32 UEFI installer",
                requiresWimSplit = requiresWimSplit,
                recommendation = if (requiresWimSplit) {
                    "Split install.wim before writing FAT32 media."
                } else {
                    "Extract ISO files to a FAT32 UEFI layout."
                },
                installImagePath = installImage?.path,
                installImageSize = installImage?.size,
            )
        } else {
            unsupported(imageName)
        }
    }

    fun preWriteNotice(imageName: String): String? =
        when (ImageClassifier.classify(imageName)) {
            ImageKind.WindowsIsoCandidate,
            ImageKind.Iso,
            -> "Select Raw / DD or an explicit Windows write method. Windows modes reject unsupported ISO contents and never fall back to raw writing."
            else -> null
        }

    fun rawWriteFinishedMessage(imageName: String): String =
        if (ImageClassifier.classify(imageName).isIso()) {
            "Raw image write finished for $imageName. Use Verify or Inspect USB to confirm the boot media. Android or Windows may offer to format or repair raw ISO media."
        } else {
            "Raw image write finished for $imageName. Use Verify to confirm the copy."
        }

    private fun unsupported(imageName: String): IsoExtractionPlan =
        IsoExtractionPlan(
            imageName = imageName,
            support = IsoExtractionSupport.Unsupported,
            recommendedMode = IsoWriteMode.RawImage,
            targetLayout = "Raw image",
            requiresWimSplit = false,
            recommendation = "Use Raw image mode.",
        )

    private fun normalizeIsoPath(path: String): String =
        path.replace('\\', '/')
            .trim()
            .trimStart('/')
            .lowercase()

    private fun ImageKind.isIso(): Boolean =
        this == ImageKind.Iso || this == ImageKind.WindowsIsoCandidate
}
