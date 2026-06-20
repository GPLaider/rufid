package io.github.rufid.format

object RecoveryVolumeLabel {
    private const val MAX_VOLUME_LABEL_CHARS = 11
    const val DEFAULT_LABEL = "USB DRIVE"

    fun fromDeviceLabel(deviceLabel: String): String {
        val withoutGenericPrefix = deviceLabel
            .trim()
            .replace(Regex("^USB\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bMass\\s+Storage\\b", RegexOption.IGNORE_CASE), "")
            .trim()
        val candidate = withoutGenericPrefix.ifBlank { deviceLabel }
        return normalize(candidate).ifBlank { DEFAULT_LABEL }
    }

    private fun normalize(value: String): String =
        value.uppercase()
            .mapNotNull { char ->
                when {
                    char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-' -> char
                    char.isWhitespace() -> ' '
                    else -> null
                }
            }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_VOLUME_LABEL_CHARS)
            .trimEnd()
}
