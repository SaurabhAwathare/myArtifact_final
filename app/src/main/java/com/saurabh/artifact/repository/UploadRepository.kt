package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.QueuedUpload
import com.saurabh.artifact.data.local.QueuedUploadDao
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadRepository @Inject constructor(
    private val queuedUploadDao: QueuedUploadDao
) {
    private val moshi = Moshi.Builder().build()
    private val listType = Types.newParameterizedType(List::class.java, Float::class.javaObjectType)
    private val adapter = moshi.adapter<List<Float>>(listType)

    suspend fun queueUpload(
        userId: String,
        username: String,
        fileUri: String,
        title: String,
        isPublic: Boolean,
        duration: Long,
        emotion: String,
        emotionTag: String = "",
        emotionConfidence: Float = 0f,
        userEmoji: String,
        prompt: String,
        redactionFilter: String = "",
        amplitudes: List<Float>
    ) {
        val upload = QueuedUpload(
            userId = userId,
            username = username,
            fileUri = fileUri,
            title = title,
            isPublic = isPublic,
            duration = duration,
            emotion = emotion,
            emotionTag = emotionTag,
            emotionConfidence = emotionConfidence,
            userEmoji = userEmoji,
            prompt = prompt,
            redactionFilter = redactionFilter,
            amplitudeDataJson = adapter.toJson(amplitudes)
        )
        queuedUploadDao.insert(upload)
    }

    suspend fun getQueuedUploads(): List<QueuedUpload> = queuedUploadDao.getAllQueuedUploads()

    suspend fun removeQueuedUpload(upload: QueuedUpload) = queuedUploadDao.delete(upload)

    fun jsonToAmplitudes(json: String): List<Float> {
        return adapter.fromJson(json) ?: emptyList()
    }
}
