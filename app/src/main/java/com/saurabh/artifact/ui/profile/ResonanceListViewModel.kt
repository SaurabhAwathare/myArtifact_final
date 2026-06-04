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
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val title: String = ""
)

@HiltViewModel
class ResonanceListViewModel @Inject constructor(
    private val userRepository: UserRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val userId: String = savedStateHandle.get<String>("userId") ?: ""
    private val type: String = savedStateHandle.get<String>("type") ?: ""
    private val title: String = savedStateHandle.get<String>("title") ?: "Resonance"

    private val _uiState = MutableStateFlow(ResonanceListUiState(title = title))
    val uiState: StateFlow<ResonanceListUiState> = _uiState.asStateFlow()

    private var lastVisible: DocumentSnapshot? = null
    private var isLastPage = false

    init {
        loadUsers()
    }

    fun loadUsers(refresh: Boolean = false) {
        if (userId.isBlank() || type.isBlank()) return
        if (refresh) {
            lastVisible = null
            isLastPage = false
        }
        
        if (isLastPage && !refresh) return
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !refresh,
                isRefreshing = refresh
            )

            userRepository.getResonanceUsers(userId, type, limit = 20, lastVisible = lastVisible)
                .onSuccess { (newUsers, nextLastVisible) ->
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
