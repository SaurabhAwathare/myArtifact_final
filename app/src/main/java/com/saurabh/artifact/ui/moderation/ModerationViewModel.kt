package com.saurabh.artifact.ui.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.EvidenceRevealResponse
import com.saurabh.artifact.model.UserReport
import com.saurabh.artifact.repository.ArtifactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModerationViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _uiState = MutableStateFlow<ModerationUiState>(ModerationUiState.Loading)
    val uiState: StateFlow<ModerationUiState> = _uiState

    init {
        loadPendingReports()
    }

    fun loadPendingReports() {
        viewModelScope.launch {
            _uiState.value = ModerationUiState.Loading
            artifactRepository.getPendingReports()
                .onSuccess { reports ->
                    if (reports.isEmpty()) {
                        _uiState.value = ModerationUiState.Empty
                    } else {
                        val reportItems = reports.map { report ->
                            val artifact = artifactRepository.getArtifactById(report.artifactId).getOrNull()
                            ReportItem(report, artifact)
                        }
                        _uiState.value = ModerationUiState.Success(reportItems)
                    }
                }
                .onFailure { error ->
                    _uiState.value = ModerationUiState.Error(error.message ?: "Failed to load reports")
                }
        }
    }

    fun resolveReport(reportId: String, artifactId: String, action: ArtifactRepository.ModerationAction) {
        viewModelScope.launch {
            artifactRepository.resolveReport(reportId, artifactId, action)
                .onSuccess {
                    loadPendingReports()
                }
                .onFailure { error ->
                    // For simplicity, we just log here. In a real app, we'd show a Snackbar.
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "MODERATION_RESOLUTION_FAILED", mapOf("reportId" to reportId), error)
                }
        }
    }

    fun revealEvidence(artifactId: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                if (state is ModerationUiState.Success) {
                    val items = state.items.map { 
                        if (it.artifact?.id == artifactId) it.copy(isRevealing = true) else it 
                    }
                    state.copy(items = items)
                } else state
            }

            artifactRepository.revealModerationEvidence(artifactId)
                .onSuccess { evidence ->
                    _uiState.update { state ->
                        if (state is ModerationUiState.Success) {
                            val items = state.items.map { 
                                if (it.artifact?.id == artifactId) it.copy(revealedEvidence = evidence, isRevealing = false) else it 
                            }
                            state.copy(items = items)
                        } else state
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                         if (state is ModerationUiState.Success) {
                            val items = state.items.map { 
                                if (it.artifact?.id == artifactId) it.copy(isRevealing = false) else it 
                            }
                            state.copy(items = items)
                        } else state
                    }
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "EVIDENCE_REVEAL_FAILED", mapOf("artifactId" to artifactId), error)
                }
        }
    }
}

sealed class ModerationUiState {
    object Loading : ModerationUiState()
    object Empty : ModerationUiState()
    data class Success(val items: List<ReportItem>) : ModerationUiState()
    data class Error(val message: String) : ModerationUiState()
}

data class ReportItem(
    val report: UserReport,
    val artifact: Artifact?,
    val revealedEvidence: EvidenceRevealResponse? = null,
    val isRevealing: Boolean = false
)
