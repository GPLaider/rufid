package io.github.rufid.format

import io.github.rufid.partition.FileSystemType

enum class FormatEngineStatus {
    PureKotlinFoundation,
    PureKotlinPlanned,
    NativeDependencyReviewRequired,
}

data class FormatPlan(
    val fileSystemType: FileSystemType,
    val label: String,
    val status: FormatEngineStatus,
) {
    companion object {
        fun forFileSystem(fileSystemType: FileSystemType, label: String = RecoveryVolumeLabel.DEFAULT_LABEL): FormatPlan {
            val status = when (fileSystemType) {
                FileSystemType.Fat32,
                FileSystemType.ExFat -> FormatEngineStatus.PureKotlinFoundation
                FileSystemType.Fat16 -> FormatEngineStatus.PureKotlinPlanned
                FileSystemType.Ntfs,
                FileSystemType.Ext2,
                FileSystemType.Ext3,
                FileSystemType.Ext4 -> FormatEngineStatus.NativeDependencyReviewRequired
            }
            return FormatPlan(fileSystemType, label, status)
        }
    }
}
