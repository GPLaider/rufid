package io.github.rufid.archive

enum class ArchiveKind {
    Zip,
    SevenZip,
    Tar,
    GZip,
    Xz,
    Wim,
    Unknown,
}

object ArchivePlan {
    fun classify(name: String): ArchiveKind {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveKind.Zip
            lower.endsWith(".7z") -> ArchiveKind.SevenZip
            lower.endsWith(".tar") -> ArchiveKind.Tar
            lower.endsWith(".gz") -> ArchiveKind.GZip
            lower.endsWith(".xz") -> ArchiveKind.Xz
            lower.endsWith(".wim") -> ArchiveKind.Wim
            else -> ArchiveKind.Unknown
        }
    }
}
