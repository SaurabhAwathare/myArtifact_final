package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

data class User(
    var id: String = "",
    var anonymousName: String = "",
    var avatarSeed: String = "",
    var avatarColor: String = "#FFD700",
    var avatarConfig: AvatarConfig = AvatarConfig(),
    var emotionalProfile: String = "Quiet Observer",
    @Deprecated("Use anonymousName for public display")
    var displayName: String = "",
    @Deprecated("Do not display publicly")
    var email: String = "",
    /** @deprecated Use avatarSeed instead */
    var profilePictureUrl: String = "",
    /** @deprecated Use avatarConfig instead */
    var avatarConfigJson: String? = null,
    /** @deprecated Use avatarConfig instead */
    var avatarConfigLegacy: String? = null,
    @Deprecated("Use avatarSeed for anonymous identity")
    var identityEmoji: String = "✨",
    @get:PropertyName("isAnonymous")
    @set:PropertyName("isAnonymous")
    var isAnonymous: Boolean = true,
    var dominantEmotion: String? = null, // Matches Firestore field name
    var usernameUpdatedAt: Timestamp? = null,
    @ServerTimestamp var createdAt: Timestamp? = null,
    @ServerTimestamp var lastSeen: Timestamp? = null,
    var emotionPreferences: Map<String, Int> = emptyMap(), // emotion -> interaction count
    
    // Social & Profile
    var bio: String = "",
    var followersCount: Int = 0,
    var followingCount: Int = 0,

    // Engagement & Retention (Ethical/Non-addictive)
    var lastActivityTimestamp: Timestamp? = null,
    var softStreakCount: Int = 0,
    var totalContributions: Int = 0,
    var lastPromptId: String = "",
    var fcmToken: String? = null,
    var isAdmin: Boolean = false,
    var metadata: Map<String, Any> = emptyMap(),
) {
    /**
     * Derives the user's current dominant emotion based on interaction history.
     */
    fun deriveDominantEmotion(): String? {
        return emotionPreferences.maxByOrNull { it.value }?.key
    }
}
