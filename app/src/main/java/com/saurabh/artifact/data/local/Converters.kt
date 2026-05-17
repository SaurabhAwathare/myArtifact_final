package com.saurabh.artifact.data.local

import androidx.room.TypeConverter
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.model.UploadStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class Converters {
    @TypeConverter
    fun fromRecordingStatus(status: RecordingStatus): String {
        return status.name
    }

    @TypeConverter
    fun toRecordingStatus(value: String): RecordingStatus {
        return try {
            RecordingStatus.valueOf(value)
        } catch (_: Exception) {
            RecordingStatus.IDLE
        }
    }

    @TypeConverter
    fun fromSyncState(status: SyncState): String = status.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = try {
        SyncState.valueOf(value)
    } catch (_: Exception) {
        SyncState.INITIALIZING
    }

    @TypeConverter
    fun fromDraftState(status: ArtifactDraftState): String = status.name

    @TypeConverter
    fun toDraftState(value: String): ArtifactDraftState = try {
        ArtifactDraftState.valueOf(value)
    } catch (_: Exception) {
        ArtifactDraftState.ERROR
    }

    @TypeConverter
    fun fromUploadStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toUploadStatus(value: String): UploadStatus = try {
        UploadStatus.valueOf(value)
    } catch (_: Exception) {
        UploadStatus.FAILED
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
}
