package com.saurabh.artifact.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    // Real-time stream of notifications for the current user
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NotificationUiState> = authRepository.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                notificationRepository.listenNotifications(user.uid)
                    .map { items -> NotificationUiState(items = items, isLoading = false) }
                    .onStart { emit(NotificationUiState(isLoading = true)) }
            } else {
                flowOf(NotificationUiState(isLoading = false))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationUiState(isLoading = true))

    /**
     * Mark a notification as read when viewed.
     */
    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            notificationRepository.markNotificationAsRead(notificationId)
        }
    }

    /**
     * Clears the awareness state by marking all notifications as read.
     */
    fun clearAwareness() {
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            notificationRepository.markAllAsRead(userId)
        }
    }
}
