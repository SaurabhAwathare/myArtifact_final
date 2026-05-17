package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranscriptionState {
    IDLE,
    UPLOADING,
    TRANSCRIBING,
    ANALYZING,
    REVIEWING,
    COMPLETED,
    ERROR
}

@Serializable
data class SubtitleMetadata(
    val format: String = "SRT",
    val language: String = "en",
    val segmentCount: Int = 0,
    val lastGeneratedAt: Long = 0
)

@Serializable
data class PrivacyScanResult(
    val piiDetected: Boolean,
    val sensitiveInfo: List<SensitiveInfo> = emptyList()
)

@Serializable
data class SensitiveInfo(
    val type: String, // e.g., "PHONE_NUMBER", "EMAIL", "ADDRESS"
    val originalText: String,
    val startChar: Int,
    val endChar: Int,
    val confidence: Float
)

@Serializable
data class TranscriptEditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val segmentId: String,
    val oldText: String,
    val newText: String,
    val editorId: String
)
