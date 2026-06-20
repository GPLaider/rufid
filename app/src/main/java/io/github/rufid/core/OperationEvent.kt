package io.github.rufid.core

data class OperationEvent(
    val title: String,
    val detail: String,
    val progress: Progress? = null,
)

