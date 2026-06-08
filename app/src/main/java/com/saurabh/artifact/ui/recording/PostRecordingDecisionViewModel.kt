package com.saurabh.artifact.ui.recording

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@HiltViewModel
class PostRecordingDecisionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostRecordingDecisionUiState())
    val uiState: StateFlow<PostRecordingDecisionUiState> = _uiState.asStateFlow()

    init {
        val encodedDraftId = savedStateHandle.get<String>("draftId")
        val draftId = encodedDraftId?.let {
            URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
        }
        _uiState.update { it.copy(draftId = draftId) }
    }
}

data class PostRecordingDecisionUiState(
    val draftId: String? = null,
    val isSaving: Boolean = false
)
