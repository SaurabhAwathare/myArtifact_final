package com.saurabh.artifact.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    authRepository: AuthRepository
) : ViewModel() {

    // Real-time stream of notifications for the current user
    @OptIn(ExperimentalCoroutinesApi::class)
    val notifications: StateFlow<List<NotificationItem>> = authRepository.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                artifactRepository.listenNotifications(user.uid)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Mark a notification as read when viewed.
     */
    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            artifactRepository.markNotificationAsRead(notificationId)
        }
    }
}
