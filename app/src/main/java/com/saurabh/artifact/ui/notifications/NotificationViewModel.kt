package com.saurabh.artifact.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val authRepository: AuthRepository,
    userRepository: UserRepository
) : ViewModel() {

    private val _additionalItems = MutableStateFlow<List<NotificationItem>>(emptyList())
    private val _lastDocument = MutableStateFlow<com.google.firebase.firestore.DocumentSnapshot?>(null)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _hasMore = MutableStateFlow(true)

    // Real-time stream of the first page of notifications
    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveHeadFlow = authRepository.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                notificationRepository.listenNotifications(user.uid, limit = 20)
                    .onEach { (_, lastDoc) ->
                        // Only update the anchor for the second page if we haven't manually loaded more yet
                        if (_lastDocument.value == null) {
                            _lastDocument.value = lastDoc
                        }
                    }
            } else {
                flowOf(emptyList<NotificationItem>() to null)
            }
        }

    val uiState: StateFlow<NotificationUiState> = combine(
        liveHeadFlow,
        _additionalItems,
        _isLoadingMore,
        _hasMore,
        userRepository.observeIgnoredUsers()
    ) { (liveItems, _), additional, loadingMore, hasMore, ignoredIds ->
        val allItems = (liveItems + additional).distinctBy { it.id }
        val filteredItems = allItems.filter { item ->
            // Legacy items (actorId == null) are always shown.
            // New items are filtered against the ignored set.
            item.actorId == null || !ignoredIds.contains(item.actorId)
        }
        NotificationUiState(
            items = filteredItems,
            isLoading = false, // Loading is handled by initial state
            isLoadingMore = loadingMore,
            hasMore = hasMore
        )
    }.onStart {
        // Initial loading state while waiting for the first snapshot
        emit(NotificationUiState(isLoading = true))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationUiState(isLoading = true))

    /**
     * Loads the next page of notifications.
     */
    fun loadMoreNotifications() {
        val userId = authRepository.currentUser.value?.uid ?: return
        if (_isLoadingMore.value || !_hasMore.value) return

        val lastDoc = _lastDocument.value ?: return

        viewModelScope.launch {
            _isLoadingMore.value = true
            
            val result = notificationRepository.getNotificationsPage(
                userId = userId,
                limit = 20,
                lastVisible = lastDoc
            )

            result.onSuccess { (newItems, newLastDoc) ->
                if (newItems.isEmpty()) {
                    _hasMore.value = false
                } else {
                    _additionalItems.value += newItems
                    _lastDocument.value = newLastDoc
                    if (newItems.size < 20) {
                        _hasMore.value = false
                    }
                }
            }.onFailure {
                // UI message handling can be added here if needed
            }

            _isLoadingMore.value = false
        }
    }

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
