package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
enum class SensitiveType {
    NAME,
    LOCATION,
    PHONE,
    EMAIL,
    ID_NUMBER,
    OTHER
}

@Serializable
data class SensitiveItem(
    val id: String,
    val type: SensitiveType,
    val originalText: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val suggestion: String? = null
)

@Serializable
data class PublishMetadata(
    val approvedAt: Long,
    val deviceId: String,
    val biometricVerified: Boolean,
    val originalDuration: Long,
    val transcriptVersion: Int
)

@Serializable
data class TranscriptRevision(
    val timestamp: Long = System.currentTimeMillis(),
    val editedBy: String = "user",
    val changeSummary: String
)
