package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

data class User(
    var id: String = "",
    var anonymousId: String = "", // Short ID like usr_9F3A2
    var anonymousName: String = "",
    var anonymousSigil: String = "", // Added for soft uniqueness
    var avatarSeed: String = "",
    var avatarColor: String = "#FFD700",
    var avatarConfig: AvatarConfig = AvatarConfig(),
    var emotionalProfile: String = "Quiet Observer",
    @get:PropertyName("isAnonymous")
    @set:PropertyName("isAnonymous")
    var isAnonymous: Boolean = true,
    var dominantEmotion: String? = null,
    var usernameUpdatedAt: Timestamp? = null,
    @ServerTimestamp var createdAt: Timestamp? = null,
    @ServerTimestamp var lastSeen: Timestamp? = null,
    var emotionPreferences: Map<String, Int> = emptyMap(),
    
    // Resonance & Profile
    var bio: String = "",
    var resonanceInCount: Int = 0,
    var resonanceOutCount: Int = 0,
    var followersCount: Int = 0, // Keep for backward compatibility/migration
    var followingCount: Int = 0, // Keep for backward compatibility/migration

    // Engagement
    var lastActivityTimestamp: Timestamp? = null,
    var softStreakCount: Int = 0,
    var totalContributions: Int = 0,
    var lastPromptId: String = "",
    
    // Missing fields causing warnings
    // Missing fields causing warnings
    var displayName: String = "",
    var fcmToken: String? = null
) {
    /**
     * Derives the user's current dominant emotion based on interaction history.
     */
    fun deriveDominantEmotion(): String? {
        return emotionPreferences.maxByOrNull { it.value }?.key
    }
}

/**
 * Sensitive data stored in users/{uid}/private/settings
 * This is NEVER exposed to other users via Firestore rules.
 */
data class UserPrivateSettings(
    var email: String = "",
    var realName: String = "", // Sourced from Google Auth, kept private
    var fcmToken: String? = null,
    var isAdmin: Boolean = false,
    var accountStatus: String = "ACTIVE", // ACTIVE, SHADOW_BANNED, BANNED
    var metadata: Map<String, Any> = emptyMap()
)
