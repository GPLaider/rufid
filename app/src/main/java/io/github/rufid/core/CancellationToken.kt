package io.github.rufid.core

import java.io.IOException

class OperationCancelledException : IOException("Operation cancelled.")

class CancellationToken private constructor(
    private val cancellable: Boolean,
) {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        if (cancellable) isCancelled = true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw OperationCancelledException()
    }

    companion object {
        fun active(): CancellationToken = CancellationToken(cancellable = true)

        val None: CancellationToken = CancellationToken(cancellable = false)
    }
}
