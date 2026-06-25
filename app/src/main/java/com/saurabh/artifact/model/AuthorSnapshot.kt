package com.saurabh.artifact.model

import androidx.compose.runtime.Immutable
import com.saurabh.artifact.BuildConfig
import android.util.Log
import kotlinx.serialization.Serializable

/**
 * A lightweight, denormalized snapshot of a user's public identity
 * at the time an artifact was created.
 */
@Immutable
@Serializable
data class AuthorSnapshot(
    val anonymousId: String = "",
    val name: String = "",
    val sigil: String = "", // Added for soft uniqueness
    val avatarSeed: String = "",
    val avatarColor: String = "#FFD700",
    val avatarConfig: AvatarConfig = AvatarConfig()
) {
    companion object {
        /**
         * Factory method to create a snapshot from a User profile.
         * Enforces defense-in-depth preconditions.
         */
        fun fromUser(user: User): AuthorSnapshot {
            // Defense in Depth: Precondition check
            val isIdentityIncomplete = user.anonymousId.isBlank() || 
                                      user.anonymousName.isBlank() || 
                                      user.anonymousSigil.isBlank()

            if (isIdentityIncomplete) {
                val errorMsg = "Attempted to create AuthorSnapshot from incomplete User identity (UID: ${user.id})"
                if (BuildConfig.DEBUG) {
                    throw IllegalStateException(errorMsg)
                } else {
                    Log.e("AuthorSnapshot", errorMsg)
                }
            }

            return AuthorSnapshot(
                anonymousId = user.anonymousId,
                name = user.anonymousName,
                sigil = user.anonymousSigil,
                avatarSeed = user.avatarSeed,
                avatarColor = user.avatarColor,
                avatarConfig = user.avatarConfig
            )
        }
    }
}
