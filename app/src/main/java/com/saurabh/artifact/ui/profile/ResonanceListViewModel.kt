package com.saurabh.artifact.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResonanceListUiState(
    val users: List<User> = emptyList(),
    val resonatingWithIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val title: String = ""
)

@HiltViewModel
class ResonanceListViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val profileInteractionUseCase: com.saurabh.artifact.domain.profile.ProfileInteractionUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val currentUserId: String? get() = authRepository.currentUser.value?.uid

    private val userId: String? = savedStateHandle.get<String>("userId")
    private val type: String? = savedStateHandle.get<String>("type")
    private val artifactId: String? = savedStateHandle.get<String>("artifactId")
    private val title: String = savedStateHandle.get<String>("title") ?: "Resonators"

    private val _uiState = MutableStateFlow(ResonanceListUiState(title = title))
    val uiState: StateFlow<ResonanceListUiState> = _uiState.asStateFlow()

    private var lastVisible: DocumentSnapshot? = null
    private var isLastPage = false

    init {
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    userRepository.observeResonatingWithIds(user.uid).collect { ids ->
                        _uiState.value = _uiState.value.copy(resonatingWithIds = ids)
                    }
                }
            }
        }
        loadUsers()
    }

    fun toggleResonance(targetUserId: String) {
        val currentUserId = authRepository.currentUser.value?.uid ?: return
        if (currentUserId == targetUserId) return

        val wasResonating = _uiState.value.resonatingWithIds.contains(targetUserId)

        viewModelScope.launch {
            profileInteractionUseCase.toggleResonance(currentUserId, targetUserId, wasResonating)
        }
    }

    fun loadUsers(refresh: Boolean = false) {
        if (artifactId.isNullOrBlank() && (userId.isNullOrBlank() || type.isNullOrBlank())) return
        
        if (refresh) {
            lastVisible = null
            isLastPage = false
        }
        
        if (isLastPage) return
        if (_uiState.value.isLoading && !refresh) return
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !refresh,
                isRefreshing = refresh
            )

            val result = if (!artifactId.isNullOrBlank()) {
                userRepository.getArtifactResonators(artifactId, limit = 20, lastVisible = lastVisible)
            } else {
                userRepository.getResonanceUsers(userId!!, type!!, limit = 20, lastVisible = lastVisible)
            }

            result.onSuccess { (newUsers, nextLastVisible) ->
                    lastVisible = nextLastVisible
                    isLastPage = newUsers.size < 20
                    
                    _uiState.value = _uiState.value.copy(
                        users = if (refresh) newUsers else _uiState.value.users + newUsers,
                        isLoading = false,
                        isRefreshing = false,
                        error = null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.message ?: "Could not find any presences"
                    )
                }
        }
    }
}
