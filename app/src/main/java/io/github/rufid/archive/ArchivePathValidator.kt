package io.github.rufid.archive

internal object ArchivePathValidator {
    fun requireSafe(path: String, directory: Boolean = false) {
        require(path.isNotBlank()) { "Archive entry path is empty." }
        require(path.none { it == '\u0000' || it.isISOControl() }) {
            "Archive entry path contains a control character."
        }
        require('\\' !in path) { "Archive entry path contains a backslash: $path" }
        require(!path.startsWith('/')) { "Archive entry path is absolute: $path" }
        require(!DRIVE_PATH.matches(path)) { "Archive entry path contains a drive prefix: $path" }
        val normalized = if (directory) path.removeSuffix("/") else path
        require(normalized.isNotEmpty()) { "Archive entry path is empty." }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Archive entry path contains an unsafe segment: $path"
        }
    }

    private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
}
