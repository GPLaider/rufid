package io.github.rufid.core

data class Progress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val phase: Phase,
) {
    val percent: Int
        get() = if (bytesTotal <= 0L) 0 else ((bytesDone * 100L) / bytesTotal).toInt()

    enum class Phase {
        Writing,
        Verifying,
        /** NTFS stream populate into sparse staging image. */
        Populating,
        /** NTFS stream byte-verify of staging image. */
        VerifyingNtfs,
        /** Sparse allocated-extent copy to USB data partition. */
        CopyingSparse,
        /** Sparse allocated-extent compare staging vs USB. */
        ComparingSparse,
        Finished,
    }
}

