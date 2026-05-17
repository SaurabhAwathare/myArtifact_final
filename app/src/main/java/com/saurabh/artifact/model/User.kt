package com.saurabh.artifact.model

import com.google.firebase.Timestamp

import com.google.firebase.firestore.ServerTimestamp

data class User(
    val id: String = "",
    val anonymousName: String = "",
    val avatarColor: String = "#FFD700", // Default Gold
    val emotionalProfile: String = "Quiet Observer",
    @Deprecated("Use anonymousName for public display")
    val displayName: String = "",
    @Deprecated("Do not display publicly")
    val email: String = "",
    /** @deprecated Use avatarConfigJson instead for anonymous identity */
    val profilePictureUrl: String = "",
    val avatarConfigJson: String? = null,
    val identityEmoji: String = "✨",
    val isAnonymous: Boolean = true,
    val dominantEmotion: String? = null, // Matches Firestore field name
    val usernameUpdatedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    val emotionPreferences: Map<String, Int> = emptyMap(), // emotion -> interaction count
    
    // Social & Profile
    val bio: String = "",
    val followersCount: Int = 0,
    val followingCount: Int = 0,

    // Engagement & Retention (Ethical/Non-addictive)
    val lastActivityTimestamp: Timestamp? = null,
    val softStreakCount: Int = 0,
    val totalContributions: Int = 0,
    val lastPromptId: String = "",
    val fcmToken: String? = null,
    val isAdmin: Boolean = false,
    val metadata: Map<String, Any> = emptyMap(),
) {
    /**
     * Derives the user's current dominant emotion based on interaction history.
     */
    fun deriveDominantEmotion(): String? {
        return emotionPreferences.maxByOrNull { it.value }?.key
    }
}
