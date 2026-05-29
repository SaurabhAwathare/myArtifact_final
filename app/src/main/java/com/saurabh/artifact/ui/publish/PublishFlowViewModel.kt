package com.saurabh.artifact.ui.publish

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.repository.PublishApprovalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PublishFlowViewModel @Inject constructor(
    private val repository: PublishApprovalRepository,
    private val publishSessionManager: com.saurabh.artifact.audio.PublishSessionManager,
    private val identityScout: IdentityScout,
    private val auth: com.google.firebase.auth.FirebaseAuth,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val draftId: String = savedStateHandle.get<String>("draftId") ?: ""

    private val _uiState = MutableStateFlow(PublishApprovalUiState())
    val uiState: StateFlow<PublishApprovalUiState> = _uiState.asStateFlow()

    init {
        loadDraft()
    }

    private fun loadDraft() {
        if (draftId.isEmpty()) {
            _uiState.update { it.copy(error = "Invalid Draft ID") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val draft = repository.getDraft(draftId)
            if (draft != null) {
                // Fetch transcript from local file
                val transcript = draft.localTranscriptPath?.let { path ->
                    try {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            val text = file.readText()
                            // For now, if it's just raw text, wrap it in a single segment
                            // In a real app, we'd parse JSON segments
                            listOf(TranscriptSegment(text = text, startMs = 0, endMs = draft.durationMs, confidence = 1.0f))
                        } else emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                
                val validation = repository.validateDraft(draft, transcript)
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        draftId = draftId,
                        title = draft.title ?: "Untitled",
                        description = draft.description ?: "",
                        emotion = draft.emotion ?: "",
                        tags = draft.tags,
                        transcript = transcript,
                        audioDurationMs = draft.durationMs,
                        isPublic = draft.isPublic,
                        isListened = draft.isListened,
                        hasSensitiveInfo = validation.hasSensitiveInfo,
                        isHighRisk = validation.isHighRisk,
                        sensitiveFlagCount = validation.sensitiveFlagCount
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, error = "Draft not found") }
            }
        }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun onDescriptionChange(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    fun onEmotionChange(emotion: String) {
        _uiState.update { it.copy(emotion = emotion) }
    }

    fun onToggleTag(tag: String) {
        _uiState.update { state ->
            val newTags = if (state.tags.contains(tag)) {
                state.tags - tag
            } else {
                state.tags + tag
            }
            state.copy(tags = newTags)
        }
    }

    fun onConfirmComfortable(confirmed: Boolean) {
        _uiState.update { it.copy(confirmedComfortable = confirmed) }
    }

    fun onConfirmSensitiveRemoved(confirmed: Boolean) {
        _uiState.update { it.copy(confirmedSensitiveRemoved = confirmed) }
    }

    fun onConfirmComplete(confirmed: Boolean) {
        _uiState.update { it.copy(confirmedComplete = confirmed) }
    }

    fun onApproveAndPublish() {
        if (!_uiState.value.canApprove) return

        val realName = auth.currentUser?.displayName
        val email = auth.currentUser?.email
        val allWarnings = mutableListOf<com.saurabh.artifact.model.ModerationWarning>()

        // 1. Scan Metadata & Transcript for Leaks
        allWarnings.addAll(identityScout.detectLeaks(_uiState.value.title, realName, email))
        allWarnings.addAll(identityScout.detectLeaks(_uiState.value.description, realName, email))
        
        _uiState.value.transcript.forEach { segment ->
            allWarnings.addAll(identityScout.detectLeaks(segment.text, realName, email))
        }

        val uniqueWarnings = allWarnings.distinctBy { it.reason }
        val riskScore = identityScout.calculateRiskScore(uniqueWarnings)

        // 2. Reflective Intervention Logic
        if (uniqueWarnings.isNotEmpty() && !_uiState.value.isPrivacyNudgeBypassed) {
            _uiState.update { it.copy(
                showPrivacyNudge = true,
                privacyWarnings = uniqueWarnings.map { w -> w.message },
                identityRiskScore = riskScore
            ) }
            return
        }

        performPublish()
    }

    fun onDismissPrivacyNudge() {
        _uiState.update { it.copy(showPrivacyNudge = false) }
    }

    fun onConfirmPublishAnyway() {
        _uiState.update { it.copy(showPrivacyNudge = false, isPrivacyNudgeBypassed = true) }
        performPublish()
    }

    private fun performPublish() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            // Save title and description first
            repository.getDraft(draftId)?.let { draft ->
                repository.updateDraft(draft.copy(
                    title = _uiState.value.title,
                    description = _uiState.value.description,
                    emotion = _uiState.value.emotion,
                    tags = _uiState.value.tags
                ))
            }
            
            // 1. Psychological Pacing (Shortened for the ambient transition)
            kotlinx.coroutines.delay(1000)

            // 2. Submit to session manager (which triggers immutable freeze and WorkManager)
            val result = publishSessionManager.approveAndPublish(draftId, _uiState.value.transcript)
            
            if (result.isSuccess) {
                // 3. Signal immediate success to trigger navigation back
                publishSessionManager.clearSession()
                _uiState.update { it.copy(
                    isLoading = false, 
                    isSuccess = true, 
                    currentState = ArtifactDraftState.APPROVED_FOR_PUBLISH 
                ) }
            } else {
                _uiState.update { it.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "Approval failed") }
            }
        }
    }
}
