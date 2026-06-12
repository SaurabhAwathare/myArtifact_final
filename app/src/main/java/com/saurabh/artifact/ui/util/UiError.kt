package com.saurabh.artifact.ui.util

/**
 * Represents a user-facing error with an optional recovery action.
 */
data class UiError(
    val message: UiText,
    val actionLabel: UiText? = null,
    val onAction: (() -> Unit)? = null
)
