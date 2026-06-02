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
    var status: ModerationStatus = ModerationStatus.SAFE,
    var score: Float = 0f,
    var categories: List<String> = emptyList(),
    var updatedAt: Timestamp = Timestamp.now(),
    var reviewId: String? = null
)

data class UserReport(
    var id: String = "",
    var artifactId: String = "",
    var commentId: String? = null, // Optional: if reporting a specific comment
    var reporterDeviceId: Int = 0, // Hashed device ID for privacy
    var reason: ReportReason = ReportReason.OTHER,
    var details: String = "",
    var createdAt: Timestamp = Timestamp.now(),
    var status: ReportStatus = ReportStatus.PENDING
)

enum class ReportStatus {
    PENDING,
    RESOLVED,
    DISMISSED
}
