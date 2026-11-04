package com.saurabh.artifact.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    val actorProfiles: Map<String, User> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _additionalItems = MutableStateFlow<List<NotificationItem>>(emptyList())
    private val _lastDocument = MutableStateFlow<DocumentSnapshot?>(null)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _hasMore = MutableStateFlow(true)

    // Reliability & State Machine tracking
    private val _inFlightIds = MutableStateFlow<Set<String>>(emptySet())
    private val _processedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _clickedPendingIds = MutableStateFlow<Set<String>>(emptySet())
    private val _currentVisibleIds = MutableStateFlow<Set<String>>(emptySet())
    private val retryCountMap = mutableMapOf<String, Int>()

    val inFlightIds: StateFlow<Set<String>> = _inFlightIds.asStateFlow()
    val processedIds: StateFlow<Set<String>> = _processedIds.asStateFlow()
    val clickedPendingIds: StateFlow<Set<String>> = _clickedPendingIds.asStateFlow()

    // Real-time stream of the first page of notifications
    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveHeadFlow = authRepository.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                notificationRepository.listenNotifications(user.uid, limit = 20)
                    .onEach { (_, lastDoc) ->
                        if (_lastDocument.value == null) {
                            _lastDocument.value = lastDoc
                        }
                    }
            } else {
                flowOf(emptyList<NotificationItem>() to null)
            }
        }
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorProfilesFlow: Flow<Map<String, User>> = liveHeadFlow
        .flatMapLatest { (liveItems, _) ->
            val actorIds = liveItems.mapNotNull { it.actorId }.filter { it.startsWith("usr_") }.distinct()
            if (actorIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(actorIds.map { actorId ->
                    userRepository.streamUserProfile(actorId)
                        .onStart { emit(null) }
                        .map { profile -> actorId to profile }
                }) { pairs ->
                    pairs.mapNotNull { (id, user) -> if (user != null) id to user else null }.toMap()
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val baseItemsFlow = combine(
        liveHeadFlow,
        _additionalItems,
        _isLoadingMore,
        _hasMore,
        userRepository.observeIgnoredUsers()
    ) { (liveItems, _), additional, loadingMore, hasMore, ignoredIds ->
        val allItems = (liveItems + additional).distinctBy { it.id }
        val filteredItems = allItems.filter { item ->
            item.actorId == null || !ignoredIds.contains(item.actorId)
        }
        Triple(filteredItems, loadingMore, hasMore)
    }

    val uiState: StateFlow<NotificationUiState> = combine(
        baseItemsFlow,
        actorProfilesFlow
    ) { (items, loadingMore, hasMore), profiles ->
        NotificationUiState(
            items = items,
            actorProfiles = profiles,
            isLoading = false,
            isLoadingMore = loadingMore,
            hasMore = hasMore
        )
    }.onStart {
        emit(NotificationUiState(isLoading = true))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationUiState(isLoading = true))

    /**
     * Called when items dwell in the viewport (continuous 500ms dwell verified).
     */
    fun onNotificationsVisible(visibleIds: List<String>) {
        if (visibleIds.isEmpty()) return
        _currentVisibleIds.value = visibleIds.toSet()

        val currentItemsMap = uiState.value.items.associateBy { it.id }
        val candidateIds = visibleIds.filter { id ->
            val item = currentItemsMap[id]
            // Must be present, currently unread in Firestore listener state, not processed, not currently in-flight
            item != null && !item.isRead && id !in _processedIds.value && id !in _inFlightIds.value
        }

        if (candidateIds.isNotEmpty()) {
            submitMarkAsRead(candidateIds)
        }
    }

    /**
     * Handles explicit card click. Preserves explicit click intent even if an in-flight batch write is currently active.
     */
    fun markAsRead(notificationId: String) {
        val item = uiState.value.items.find { it.id == notificationId }
        if (item != null && item.isRead) return
        if (notificationId in _processedIds.value) return

        // Register explicit user click intent
        _clickedPendingIds.update { it + notificationId }

        // If currently in-flight, do not initiate duplicate write; intent will be fulfilled when in-flight returns
        if (notificationId in _inFlightIds.value) return

        submitMarkAsRead(listOf(notificationId))
    }

    private fun submitMarkAsRead(targetIds: List<String>) {
        val candidates = targetIds.filter { id ->
            id !in _processedIds.value && id !in _inFlightIds.value
        }.distinct()

        if (candidates.isEmpty()) return

        // Mark as IN_FLIGHT atomically
        _inFlightIds.update { it + candidates }

        viewModelScope.launch {
            try {
                val result = notificationRepository.markNotificationsAsRead(candidates)
                val successSet = result.successfulIds.toSet()
                val failedSet = result.failedIds.toSet()

                // Release in-flight lock for candidates in this batch pass before handling retries
                _inFlightIds.update { it - candidates.toSet() }

                if (successSet.isNotEmpty()) {
                    _processedIds.update { it + successSet }
                    _clickedPendingIds.update { it - successSet }
                }

                if (failedSet.isNotEmpty()) {
                    val (clickedFailed, nonClickedFailed) = failedSet.partition { it in _clickedPendingIds.value }

                    // Immediately retry clicked failed items if under retry limit
                    for (id in clickedFailed) {
                        val retries = retryCountMap.getOrDefault(id, 0)
                        if (retries < 3) {
                            retryCountMap[id] = retries + 1
                            submitMarkAsRead(listOf(id))
                        }
                    }

                    // Schedule controlled retry pass for visible non-clicked items
                    val retryableNonClicked = nonClickedFailed.filter { id ->
                        val retries = retryCountMap.getOrDefault(id, 0)
                        retries < 3 && id in _currentVisibleIds.value
                    }

                    if (retryableNonClicked.isNotEmpty()) {
                        retryableNonClicked.forEach { id ->
                            retryCountMap[id] = retryCountMap.getOrDefault(id, 0) + 1
                        }
                        scheduleRetryPass(retryableNonClicked, delayMs = 3000L)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _inFlightIds.update { it - candidates.toSet() }
            }
        }
    }

    private fun scheduleRetryPass(ids: List<String>, delayMs: Long) {
        viewModelScope.launch {
            delay(delayMs)
            val candidates = ids.filter { id ->
                id in _currentVisibleIds.value && id !in _processedIds.value && id !in _inFlightIds.value
            }
            if (candidates.isNotEmpty()) {
                submitMarkAsRead(candidates)
            }
        }
    }

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
     * Legacy awareness method retained for explicit invocation if needed.
     */
    fun clearAwareness() {
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            notificationRepository.markAllAsRead(userId)
        }
    }
}
