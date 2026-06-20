package io.github.rufid.core

object ByteFormatter {
    private val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    fun format(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return "%.1f %s".format(value, units[unit])
    }
}

