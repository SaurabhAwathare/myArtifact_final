package com.saurabh.artifact.util

import com.saurabh.artifact.model.SharePayload

/**
 * Utility to format artifact share text.
 * Centralizes the layout and content of the share message.
 */
object ShareFormatter {
    fun formatShareText(payload: SharePayload): String {
        return buildString {
            append("Listen to this Artifact on Artifact\n\n")
            append("\"${payload.title}\"\n\n")
            append("by ${payload.authorName}")
            if (payload.authorSigil.isNotEmpty()) {
                append(" ${payload.authorSigil}")
            }
            payload.shareUrl?.let { url ->
                append("\n\n$url")
            }
        }
    }
}
