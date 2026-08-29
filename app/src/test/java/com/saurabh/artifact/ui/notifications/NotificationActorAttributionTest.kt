package com.saurabh.artifact.ui.notifications

import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActorAttributionTest {

    private val notificationRepository = mockk<NotificationRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    
    private val testDispatcher = StandardTestDispatcher()
    private val currentUserId = "current-user"
    private val mockUser = mockk<com.google.firebase.auth.FirebaseUser> {
        every { uid } returns currentUserId
    }

    private val liveNotificationsFlow = MutableStateFlow<Pair<List<NotificationItem>, com.google.firebase.firestore.DocumentSnapshot?>>(emptyList<NotificationItem>() to null)
    private val ignoredUsersFlow = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        every { authRepository.currentUser } returns MutableStateFlow(mockUser)
        every { authRepository.currentUserId } returns currentUserId
        
        every { notificationRepository.listenNotifications(currentUserId, any()) } returns liveNotificationsFlow
        every { userRepository.observeIgnoredUsers() } returns ignoredUsersFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `notifications from ignored actors should be filtered out`() = runTest {
        val ignoredActorId = "ignored-actor"
        val normalActorId = "normal-actor"
        
        val notifications = listOf(
            NotificationItem(id = "1", actorId = ignoredActorId, message = "Ignored"),
            NotificationItem(id = "2", actorId = normalActorId, message = "Normal")
        )
        
        liveNotificationsFlow.value = notifications to null
        ignoredUsersFlow.value = setOf(ignoredActorId)
        
        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        advanceUntilIdle()
        
        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.items.size)
        assertEquals("2", uiState.items[0].id)
    }

    @Test
    fun `legacy notifications with null actorId should always be visible`() = runTest {
        val notifications = listOf(
            NotificationItem(id = "1", actorId = null, message = "Legacy"),
            NotificationItem(id = "2", actorId = "some-actor", message = "New")
        )
        
        liveNotificationsFlow.value = notifications to null
        // Even if some-actor is ignored, legacy stays
        ignoredUsersFlow.value = setOf("some-actor")
        
        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        advanceUntilIdle()
        
        val uiState = viewModel.uiState.value
        // Only "New" should be filtered if ignored
        assertEquals(1, uiState.items.size)
        assertEquals("1", uiState.items[0].id)
    }

    @Test
    fun `unignoring an actor should restore their notifications reactively`() = runTest {
        val actorId = "actor-123"
        val notifications = listOf(
            NotificationItem(id = "1", actorId = actorId, message = "Hi")
        )
        
        liveNotificationsFlow.value = notifications to null
        ignoredUsersFlow.value = setOf(actorId)
        
        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        advanceUntilIdle()
        
        assertTrue("Should be hidden when ignored", viewModel.uiState.value.items.isEmpty())
        
        ignoredUsersFlow.value = emptySet()
        advanceUntilIdle()
        
        assertEquals("Should be visible after unignore", 1, viewModel.uiState.value.items.size)
        assertEquals("1", viewModel.uiState.value.items[0].id)
    }

    @Test
    fun `pagination should work correctly with filtering`() = runTest {
        val ignoredActorId = "bad-actor"
        val page1 = listOf(
            NotificationItem(id = "1", actorId = ignoredActorId),
            NotificationItem(id = "2", actorId = "good-actor")
        )
        val page2 = listOf(
            NotificationItem(id = "3", actorId = ignoredActorId),
            NotificationItem(id = "4", actorId = "good-actor")
        )
        
        val lastDoc1 = mockk<com.google.firebase.firestore.DocumentSnapshot>()
        liveNotificationsFlow.value = page1 to lastDoc1
        ignoredUsersFlow.value = setOf(ignoredActorId)
        
        coEvery { notificationRepository.getNotificationsPage(currentUserId, any(), lastDoc1) } returns Result.success(page2 to null)
        
        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        advanceUntilIdle()
        
        assertEquals(1, viewModel.uiState.value.items.size) // Only item 2
        
        viewModel.loadMoreNotifications()
        advanceUntilIdle()
        
        val finalItems = viewModel.uiState.value.items
        assertEquals(2, finalItems.size) // Item 2 and Item 4
        assertTrue(finalItems.any { it.id == "2" })
        assertTrue(finalItems.any { it.id == "4" })
    }
}
