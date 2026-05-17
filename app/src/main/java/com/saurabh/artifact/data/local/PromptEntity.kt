package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt

@Entity(tableName = "prompts")
data class PromptEntity(
    @PrimaryKey val id: String,
    val question: String,
    val category: PromptCategory,
    val tone: EmotionalTone,
    val mood: String? = null,
    val isFavorite: Boolean = false,
    val usageCount: Int = 0,
    val lastUsedTimestamp: Long = 0
)

fun PromptEntity.toDomainModel(): ReflectionPrompt {
    return ReflectionPrompt(
        id = id,
        question = question,
        category = category,
        tone = tone,
        mood = mood,
        isFavorite = isFavorite,
        usageCount = usageCount
    )
}

fun ReflectionPrompt.toEntity(): PromptEntity {
    return PromptEntity(
        id = id,
        question = question,
        category = category,
        tone = tone,
        mood = mood,
        isFavorite = isFavorite,
        usageCount = usageCount
    )
}
