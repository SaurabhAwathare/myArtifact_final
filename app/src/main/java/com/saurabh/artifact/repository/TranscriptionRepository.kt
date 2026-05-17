package com.saurabh.artifact.repository

import com.google.firebase.functions.FirebaseFunctions
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.TranscriptionState
import com.saurabh.artifact.data.local.DraftDao
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

@Singleton
class TranscriptionRepository @Inject constructor(
    private val functions: FirebaseFunctions,
    private val draftDao: DraftDao
) {

    /**
     * Triggers remote transcription via Firebase Cloud Functions.
     */
    suspend fun requestRemoteTranscription(draftId: String, audioUrl: String): Result<String> {
        return try {
            val data = hashMapOf(
                "draftId" to draftId,
                "audioUrl" to audioUrl
            )
            
            val result = functions
                .getHttpsCallable("transcribeAudio")
                .call(data)
                .await()
            
            val jobId = result.data as? String ?: throw Exception("Invalid response from cloud function")
            Result.success(jobId)
        } catch (e: Exception) {
            Log.e("TranscriptionRepo", "Failed to request transcription", e)
            Result.failure(e)
        }
    }

    /**
     * Updates a local draft with new transcript segments.
     */
    suspend fun updateLocalTranscript(draftId: String, segments: List<TranscriptSegment>) {
        val draft = draftDao.getDraftById(draftId) ?: return
        // In a real app, we'd store the transcript segments in a dedicated table or as a JSON blob in the draft
        // For now, we update the draft entity's transcription state
        draftDao.update(draft.copy(
            transcriptionState = "REVIEWING" // Mapping state to string for Room persistence if not using converters
        ))
    }

    /**
     * Generates subtitles in SRT format from segments.
     */
    fun generateSrt(segments: List<TranscriptSegment>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { index, segment ->
            sb.append("${index + 1}\n")
            sb.append("${formatTimestamp(segment.startMs)} --> ${formatTimestamp(segment.endMs)}\n")
            sb.append("${segment.text}\n\n")
        }
        return sb.toString()
    }

    private fun formatTimestamp(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
