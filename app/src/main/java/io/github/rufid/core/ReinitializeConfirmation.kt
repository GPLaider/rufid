package io.github.rufid.core

object ReinitializeConfirmation {
    fun accepts(value: String): Boolean {
        val normalized = value.trim()
        return normalized.equals("R", ignoreCase = true) ||
            normalized.equals("REINITIALIZE", ignoreCase = true)
    }
}
