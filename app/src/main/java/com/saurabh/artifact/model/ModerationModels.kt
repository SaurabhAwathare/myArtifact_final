package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import kotlinx.serialization.Serializable

@Serializable
enum class ModerationStatus {
    SAFE,           // Content is visible to all
    HIDDEN          // Content is hidden from all users except the author
}

enum class ReportReason {
    HARASSMENT,
    SELF_HARM,
    HATE_SPEECH,
    SEXUAL_CONTENT,
    PII_EXPOSURE,
    SPAM,
    OTHER
}

data class ModerationMetadata(
    val status: ModerationStatus = ModerationStatus.SAFE,
    val score: Float = 0f,
    val categories: List<String> = emptyList(),
    val updatedAt: Timestamp = Timestamp.now(),
    val reviewId: String? = null
)

data class UserReport(
    val id: String = "",
    val artifactId: String = "",
    val reporterDeviceId: Int = 0, // Hashed device ID for privacy
    val reason: ReportReason = ReportReason.OTHER,
    val details: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val status: ReportStatus = ReportStatus.PENDING
)

enum class ReportStatus {
    PENDING,
    RESOLVED,
    DISMISSED
}
