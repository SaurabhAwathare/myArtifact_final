package com.saurabh.artifact.presentation.publish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.EmotionalRiskAssessment
import com.saurabh.artifact.model.FlaggedSegment
import com.saurabh.artifact.nlp.EmotionAnalyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import com.saurabh.artifact.audio.analysis.RedactionLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PublishFlowViewModel @Inject constructor(
    private val draftDao: DraftDao,
    private val orchestrator: PublishingOrchestrator,
    private val emotionAnalyzer: EmotionAnalyzer
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmotionalConfirmationUiState())
    val uiState: StateFlow<EmotionalConfirmationUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            val draft = draftDao.getDraftById(draftId) ?: return@launch
            
            val transcript = draft.localTranscriptPath?.let { path ->
                val file = java.io.File(path)
                if (file.exists()) file.readText() else null
            }
            
            val flaggedSegments = draft.sensitiveEntitiesJson?.let { json ->
                try {
                    Json.decodeFromString<List<FlaggedSegment>>(json)
                } catch (_: Exception) {
                    emptyList<FlaggedSegment>()
                }
            } ?: emptyList()

            _uiState.update { it.copy(
                draftId = draftId,
                currentState = draft.draftState,
                transcript = transcript,
                flaggedSegments = flaggedSegments,
                ritualState = mapDraftStateToRitualState(draft.draftState, draft, transcript, flaggedSegments)
            ) }

            // ... risk assessment logic ...
        }
    }

    private fun mapDraftStateToRitualState(
        state: ArtifactDraftState, 
        @Suppress("UNUSED_PARAMETER") draft: com.saurabh.artifact.data.local.ArtifactDraftEntity,
        transcript: String?,
        flaggedSegments: List<FlaggedSegment>
    ): PublishRitualState {
        return when (state) {
            ArtifactDraftState.PROCESSING, 
            ArtifactDraftState.NORMALIZING, 
            ArtifactDraftState.WAVEFORM_GENERATION, 
            ArtifactDraftState.TRANSCRIBING, 
            ArtifactDraftState.PRIVACY_SCANNING, 
            ArtifactDraftState.SAFETY_CHECK -> PublishRitualState.Processing
            
            ArtifactDraftState.READY_TO_REVIEW,
            ArtifactDraftState.REVIEWING -> PublishRitualState.Reviewing(transcript ?: "")
            
            ArtifactDraftState.PRIVACY_CHECK -> PublishRitualState.PrivacyCheck(flaggedSegments)

            ArtifactDraftState.EMOTIONAL_SELECTION -> PublishRitualState.EmotionalSelection(null)
            ArtifactDraftState.FINAL_RITUAL -> PublishRitualState.FinalConfirmation
            ArtifactDraftState.UPLOADING -> PublishRitualState.Publishing
            ArtifactDraftState.PUBLISHED -> PublishRitualState.Success
            else -> PublishRitualState.Idle
        }
    }

    fun onHoldProgressChanged(progress: Float) {
        _uiState.update { it.copy(holdToPublishProgress = progress) }
        if (progress >= 1f) {
            confirmPublish()
        }
    }

    fun moveToState(ritualState: PublishRitualState) {
        _uiState.update { it.copy(ritualState = ritualState) }
        
        // Optionally update the DB state if we want persistence of the ritual stage
        val draftState = when (ritualState) {
            is PublishRitualState.Reviewing -> ArtifactDraftState.REVIEWING
            is PublishRitualState.PrivacyCheck -> ArtifactDraftState.PRIVACY_CHECK
            is PublishRitualState.FinalConfirmation -> ArtifactDraftState.FINAL_RITUAL
            else -> null
        }
        
        draftState?.let { s ->
            viewModelScope.launch {
                draftDao.updateDraftState(_uiState.value.draftId, s)
            }
        }
    }

    fun confirmPublish() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isProcessing = true) }
            
            draftDao.updateEmotionalConfirmation(state.draftId, true, state.publishConfidence)
            orchestrator.requestPublishApproval(state.draftId)
            
            _uiState.update { it.copy(isProcessing = false, currentState = ArtifactDraftState.PENDING_APPROVAL) }
        }
    }

    fun savePrivately() {
        viewModelScope.launch {
            val state = _uiState.value
            draftDao.updateDraftState(state.draftId, ArtifactDraftState.SAVED_LOCALLY)
            _uiState.update { it.copy(currentState = ArtifactDraftState.SAVED_LOCALLY) }
        }
    }

    private fun startCooldown(draftId: String) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            val expiry = System.currentTimeMillis() + 10 * 60 * 1000 // 10 minutes
            draftDao.updateCooldown(draftId, expiry)
            _uiState.update { it.copy(isCooldownActive = true) }
            
            while (System.currentTimeMillis() < expiry) {
                val remaining = (expiry - System.currentTimeMillis()) / 1000
                _uiState.update { it.copy(cooldownRemainingSeconds = remaining) }
                delay(1000)
            }
            
            _uiState.update { it.copy(isCooldownActive = false, cooldownRemainingSeconds = 0) }
            draftDao.updateCooldown(draftId, null)
        }
    }
}
