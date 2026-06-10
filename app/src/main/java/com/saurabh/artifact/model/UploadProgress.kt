package com.saurabh.artifact.model

interface UploadProgress {
    val uploadedBytes: Long
    val totalBytes: Long
}

val UploadProgress.progress: Float
    get() = if (totalBytes > 0) {
        (uploadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    } else 0f
