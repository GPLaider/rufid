package io.github.rufid.windows

import io.github.rufid.core.ByteFormatter

data class WindowsIsoPlan(
    val installWimSize: Long?,
    val targetFileSystemAllowsLargeFiles: Boolean,
) {
    val needsWimSplit: Boolean
        get() = installWimSize != null && installWimSize > FAT32_MAX_FILE_SIZE && !targetFileSystemAllowsLargeFiles

    fun summary(): String =
        when {
            installWimSize == null -> "No install.wim size known yet."
            needsWimSplit -> "install.wim is ${ByteFormatter.format(installWimSize)} and must be split for FAT32."
            else -> "install.wim is ${ByteFormatter.format(installWimSize)} and does not require FAT32 splitting."
        }

    companion object {
        const val FAT32_MAX_FILE_SIZE: Long = 4L * 1024L * 1024L * 1024L - 1L
    }
}

