package com.saurabh.artifact.ui.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.UserReport
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.CommentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModerationViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val commentRepository: CommentRepository,
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
                            val artifact = artifactRepository.getArtifactById(report.artifactId)
                            val comment = report.commentId?.let { commentRepository.getCommentById(it) }
                            ReportItem(report, artifact, comment)
                        }
                        _uiState.value = ModerationUiState.Success(reportItems)
                    }
                }
                .onFailure { error ->
                    _uiState.value = ModerationUiState.Error(error.message ?: "Failed to load reports")
                }
        }
    }

    fun resolveReport(reportId: String, artifactId: String, action: ArtifactRepository.ModerationAction, commentId: String? = null) {
        viewModelScope.launch {
            artifactRepository.resolveReport(reportId, artifactId, action, commentId)
                .onSuccess {
                    loadPendingReports()
                }
                .onFailure { error ->
                    // For simplicity, we just log here. In a real app, we'd show a Snackbar.
                    android.util.Log.e("ModerationViewModel", "Resolution failed", error)
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
    val comment: ArtifactComment? = null
)
