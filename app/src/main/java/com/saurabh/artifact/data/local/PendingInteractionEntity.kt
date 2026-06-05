package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_interactions")
data class PendingInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artifactId: String,
    val interactionType: String, // "REACTION", "SAVE", "FOLLOW"
    val action: String, // "ADD", "REMOVE"
    val metadata: String? = null, // e.g., the ReactionType ID
    val createdAt: Long = System.currentTimeMillis()
)

object InteractionType {
    const val REACTION = "REACTION"
    const val SAVE = "SAVE"
    const val FOLLOW = "FOLLOW"
}

object InteractionAction {
    const val ADD = "ADD"
    const val REMOVE = "REMOVE"
}
