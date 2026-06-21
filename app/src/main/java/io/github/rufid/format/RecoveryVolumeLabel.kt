package io.github.rufid.format

object RecoveryVolumeLabel {
    private const val MAX_VOLUME_LABEL_CHARS = 11
    const val DEFAULT_LABEL = "USB DRIVE"

    fun fromDeviceLabel(deviceLabel: String): String {
        val candidate = deviceLabel
            .trim()
            .replace(Regex("^USB\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bMass\\s+Storage\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*Gen\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bGen\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bUSB\\s*\\d+(?:\\.\\d+)?\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d+\\.\\d+\\b"), "")
            .trim()
        val normalized = normalize(candidate.ifBlank { deviceLabel })
        return normalized.ifBlank { DEFAULT_LABEL }
            .take(MAX_VOLUME_LABEL_CHARS)
            .trimEnd()
    }

    private fun normalize(value: String): String =
        value.uppercase()
            .mapNotNull { char ->
                when {
                    char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-' -> char
                    char.isWhitespace() -> ' '
                    else -> ' '
                }
            }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
