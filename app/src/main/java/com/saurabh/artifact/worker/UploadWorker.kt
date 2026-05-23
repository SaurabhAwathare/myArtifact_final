package com.saurabh.artifact.worker

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.UploadRepository
import com.saurabh.artifact.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val artifactRepository: ArtifactRepository,
    private val uploadRepository: UploadRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // 1. Process explicit work input (if any)
        val userId = inputData.getString("userId")
        if (userId != null) {
            val result = processSingleUpload(
                userId = userId,
                username = inputData.getString("username") ?: "Anonymous",
                fileUriString = inputData.getString("fileUri") ?: "",
                title = inputData.getString("title") ?: "",
                isPublic = inputData.getBoolean("isPublic", true),
                duration = inputData.getLong("duration", 0L),
                emotion = inputData.getString("emotion") ?: "",
                emotionTag = inputData.getString("emotionTag") ?: "",
                emotionConfidence = inputData.getFloat("emotionConfidence", 0f),
                avatarSeed = inputData.getString("avatarSeed") ?: "",
                prompt = inputData.getString("prompt") ?: "",
                redactionFilter = inputData.getString("redactionFilter") ?: "",
                amplitudes = inputData.getFloatArray("amplitudes")?.toList() ?: emptyList()
            )
            if (result == Result.retry()) return result
        }

        // 2. Process background queue
        val queuedUploads = uploadRepository.getQueuedUploads()
        var hasFailure = false

        for (upload in queuedUploads) {
            val result = processSingleUpload(
                userId = upload.userId,
                username = upload.username,
                fileUriString = upload.fileUri,
                title = upload.title,
                isPublic = upload.isPublic,
                duration = upload.duration,
                emotion = upload.emotion,
                emotionTag = upload.emotionTag,
                emotionConfidence = upload.emotionConfidence,
                avatarSeed = upload.avatarSeed,
                prompt = upload.prompt,
                redactionFilter = upload.redactionFilter,
                amplitudes = uploadRepository.jsonToAmplitudes(upload.amplitudeDataJson)
            )

            if (result == Result.success()) {
                uploadRepository.removeQueuedUpload(upload)
            } else {
                hasFailure = true
            }
        }

        return if (hasFailure) Result.retry() else Result.success()
    }

    private suspend fun processSingleUpload(
        userId: String,
        username: String,
        fileUriString: String,
        title: String,
        isPublic: Boolean,
        duration: Long,
        emotion: String,
        emotionTag: String = "",
        emotionConfidence: Float = 0f,
        avatarSeed: String,
        prompt: String,
        redactionFilter: String = "",
        amplitudes: List<Float>
    ): Result {
        if (fileUriString.isBlank()) return Result.failure()

        val file = File(fileUriString)
        val uploadFile = if (file.exists()) {
            file
        } else {
            val uri = fileUriString.toUri()
            File(uri.path ?: "")
        }

        if (!uploadFile.exists() || uploadFile.length() <= 0) {
            return Result.failure()
        }

        val finalUri = Uri.fromFile(uploadFile)

        return try {
            val result = artifactRepository.uploadArtifact(
                userId = userId,
                username = username,
                audioFileUri = finalUri,
                title = title,
                isPublic = isPublic,
                duration = duration,
                emotion = emotion,
                emotionTag = emotionTag,
                emotionConfidence = emotionConfidence,
                prompt = prompt,
                redactionFilter = redactionFilter,
                avatarSeed = avatarSeed,
                amplitudeData = amplitudes
            )

            if (result.isSuccess) {
                NotificationHelper.showUploadSuccessNotification(applicationContext, title)
                Result.success()
            } else {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
