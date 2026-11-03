package com.saurabh.artifact.ui.notifications

import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        backgroundScope.launch { viewModel.uiState.collect {} }
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
        backgroundScope.launch { viewModel.uiState.collect {} }
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
        backgroundScope.launch { viewModel.uiState.collect {} }
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
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        
        assertEquals(1, viewModel.uiState.value.items.size) // Only item 2
        
        viewModel.loadMoreNotifications()
        advanceUntilIdle()
        
        val finalItems = viewModel.uiState.value.items
        assertEquals(2, finalItems.size) // Item 2 and Item 4
        assertTrue(finalItems.any { it.id == "2" })
        assertTrue(finalItems.any { it.id == "4" })
    }

    @Test
    fun `notifications should emit immediately without waiting for actor profile resolution`() = runTest {
        val pendingActorId = "usr_pending"
        val notifications = listOf(
            NotificationItem(id = "notif_1", actorId = pendingActorId, message = "COMMENT|Artifact Title")
        )

        val unrespondingProfileFlow = MutableSharedFlow<User?>()
        every { userRepository.streamUserProfile(pendingActorId) } returns unrespondingProfileFlow

        liveNotificationsFlow.value = notifications to null

        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals("Items should emit immediately", 1, uiState.items.size)
        assertEquals("notif_1", uiState.items[0].id)
        assertNull("Actor profile map should be empty prior to profile stream emission", uiState.actorProfiles[pendingActorId])
    }

    @Test
    fun `actor profile emission should dynamically enrich existing notification presentation`() = runTest {
        val actorId = "usr_rich"
        val notifications = listOf(
            NotificationItem(id = "notif_2", actorId = actorId, message = "RESONANCE|1")
        )

        val profileFlow = MutableStateFlow<User?>(null)
        every { userRepository.streamUserProfile(actorId) } returns profileFlow

        liveNotificationsFlow.value = notifications to null

        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.items.size)
        assertNull(viewModel.uiState.value.actorProfiles[actorId])

        profileFlow.value = User(id = actorId, anonymousName = "Cosmic Traveler", anonymousSigil = "✦")
        advanceUntilIdle()

        val updatedProfiles = viewModel.uiState.value.actorProfiles
        assertNotNull(updatedProfiles[actorId])
        assertEquals("Cosmic Traveler", updatedProfiles[actorId]?.anonymousName)
    }

    @Test
    fun `missing profile should not prevent notification from remaining visible`() = runTest {
        val missingActorId = "usr_missing"
        val notifications = listOf(
            NotificationItem(id = "notif_3", actorId = missingActorId, message = "NEW_ARTIFACT|Mystic Echoes")
        )

        every { userRepository.streamUserProfile(missingActorId) } returns flowOf(null)

        liveNotificationsFlow.value = notifications to null

        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals("Notification must remain visible even if profile is missing", 1, uiState.items.size)
        assertEquals("notif_3", uiState.items[0].id)
        assertNull("Missing profile produces null/absent profile entry", uiState.actorProfiles[missingActorId])
    }

    @Test
    fun `multiple actor profiles should resolve non-blockingly as each stream emits`() = runTest {
        val actorA = "usr_fast"
        val actorB = "usr_slow"
        val notifications = listOf(
            NotificationItem(id = "notif_A", actorId = actorA, message = "PRESENCE_RESONATED"),
            NotificationItem(id = "notif_B", actorId = actorB, message = "USERNAME_UPDATED|Nova")
        )

        val flowA = MutableStateFlow<User?>(null)
        val flowB = MutableStateFlow<User?>(null)

        every { userRepository.streamUserProfile(actorA) } returns flowA
        every { userRepository.streamUserProfile(actorB) } returns flowB

        liveNotificationsFlow.value = notifications to null

        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.items.size)

        flowA.value = User(id = actorA, anonymousName = "Fast User")
        advanceUntilIdle()

        assertEquals("Fast User", viewModel.uiState.value.actorProfiles[actorA]?.anonymousName)
        assertNull(viewModel.uiState.value.actorProfiles[actorB])

        flowB.value = User(id = actorB, anonymousName = "Slow User")
        advanceUntilIdle()

        assertEquals("Slow User", viewModel.uiState.value.actorProfiles[actorB]?.anonymousName)
    }

    @Test
    fun `liveHeadFlow should share single upstream subscription to notification repository`() = runTest {
        val notifications = listOf(
            NotificationItem(id = "notif_share", actorId = "usr_share", message = "COMMENT|Shared Test")
        )

        every { userRepository.streamUserProfile("usr_share") } returns flowOf(null)
        liveNotificationsFlow.value = notifications to null

        val viewModel = NotificationViewModel(notificationRepository, authRepository, userRepository)

        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        verify(exactly = 1) { notificationRepository.listenNotifications(currentUserId, 20) }
    }
}
