package com.saurabh.artifact.data.local

import androidx.room.TypeConverter
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.SyncStatus
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromDraftStatus(status: DraftStatus): String = Json.encodeToString(status)

    @TypeConverter
    fun toDraftStatus(value: String): DraftStatus = try {
        Json.decodeFromString(value)
    } catch (_: Exception) {
        DraftStatus()
    }

    @TypeConverter
    fun fromArtifactLifecycle(value: ArtifactLifecycle): String = value.name

    @TypeConverter
    fun toArtifactLifecycle(value: String): ArtifactLifecycle = try {
        ArtifactLifecycle.valueOf(value)
    } catch (_: Exception) {
        ArtifactLifecycle.RECORDING
    }

    @TypeConverter
    fun fromPromptCategory(category: PromptCategory): String = category.name

    @TypeConverter
    fun toPromptCategory(value: String): PromptCategory = try {
        PromptCategory.valueOf(value)
    } catch (_: Exception) {
        PromptCategory.SELF_REFLECTION
    }

    @TypeConverter
    fun fromEmotionalTone(tone: EmotionalTone): String = tone.name

    @TypeConverter
    fun toEmotionalTone(value: String): EmotionalTone = try {
        EmotionalTone.valueOf(value)
    } catch (_: Exception) {
        EmotionalTone.REFLECTIVE
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = try {
        Json.decodeFromString(value)
    } catch (_: Exception) {
        emptyList()
    }

    @TypeConverter
    fun fromFloatList(value: List<Float>): String = Json.encodeToString(value)

    @TypeConverter
    fun toFloatList(value: String): List<Float> = try {
        Json.decodeFromString(value)
    } catch (_: Exception) {
        emptyList()
    }

    @TypeConverter
    fun fromEffortMap(value: Map<Float, Long>): String = Json.encodeToString(value)

    @TypeConverter
    fun toEffortMap(value: String): Map<Float, Long> = try {
        Json.decodeFromString(value)
    } catch (_: Exception) {
        emptyMap()
    }

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = Json.encodeToString(status)

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = try {
        Json.decodeFromString(value)
    } catch (_: Exception) {
        SyncStatus.Queued
    }
}
