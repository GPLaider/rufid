package io.github.rufid.core

enum class ImageKind {
    RawImage,
    Iso,
    Dmg,
    WindowsIsoCandidate,
    RaspberryPiImage,
    Archive,
    Unknown,
}

object ImageClassifier {
    fun classify(name: String): ImageKind {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".img") || lower.endsWith(".bin") -> ImageKind.RawImage
            lower.endsWith(".dmg") -> ImageKind.Dmg
            lower.contains("raspios") || lower.contains("raspberry") -> ImageKind.RaspberryPiImage
            lower.endsWith(".iso") && (lower.contains("windows") || lower.contains("win11") || lower.contains("win10")) ->
                ImageKind.WindowsIsoCandidate
            lower.endsWith(".iso") -> ImageKind.Iso
            lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".tar") ||
                lower.endsWith(".gz") || lower.endsWith(".xz") -> ImageKind.Archive
            else -> ImageKind.Unknown
        }
    }
}
