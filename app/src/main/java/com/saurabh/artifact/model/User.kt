package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.saurabh.artifact.util.SecureString

data class User(
    val id: String = "",
    val anonymousId: String = "", // Short ID like usr_9F3A2
    val anonymousName: String = "",
    val anonymousSigil: String = "", // Added for soft uniqueness
    val avatarSeed: String = "",
    val avatarColor: String = "#FFD700",
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val emotionalProfile: String = "Quiet Observer",
    @get:PropertyName("isAnonymous")
    val isAnonymous: Boolean = true,
    val dominantEmotion: String? = null,
    val usernameUpdatedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    val emotionPreferences: Map<String, Long> = emptyMap(),
    
    // Resonance & Profile
    val bio: String = "",
    val resonanceInCount: Long = 0,
    val resonanceOutCount: Long = 0,
    val followersCount: Long = 0, // Keep for backward compatibility/migration
    val followingCount: Long = 0, // Keep for backward compatibility/migration

    // Engagement
    val lastActivityTimestamp: Timestamp? = null,
    val softStreakCount: Long = 0,
    val totalContributions: Long = 0,
    val lastPromptId: String = "",
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
    @get:Exclude @set:Exclude
    var secureEmail: SecureString = SecureString.empty(),
    @get:Exclude @set:Exclude
    var secureRealName: SecureString = SecureString.empty(),
    
    val fcmToken: String? = null,
    val isAdmin: Boolean = false,
    val accountStatus: String = "ACTIVE", // ACTIVE, SHADOW_BANNED, BANNED
    val metadata: Map<String, Any> = emptyMap()
) {
    // Firestore compatibility properties
    @get:PropertyName("email")
    @set:PropertyName("email")
    var email: String
        get() = secureEmail.toUnsecureString()
        set(value) { secureEmail = SecureString.fromString(value) }

    @get:PropertyName("realName")
    @set:PropertyName("realName")
    var realName: String
        get() = secureRealName.toUnsecureString()
        set(value) { secureRealName = SecureString.fromString(value) }
}
