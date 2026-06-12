package com.saurabh.artifact.service

import android.content.Context
import android.os.Build
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class PersonalizationEngineTest {

    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val userSettingsFlow = MutableStateFlow(UserSettings(dataCollectionConsent = false))

    private lateinit var engine: PersonalizationEngine
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.userSettings } returns userSettingsFlow
        engine = PersonalizationEngine(context, settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `recordInteraction should do nothing when consent is false`() = runTest {
        userSettingsFlow.value = UserSettings(dataCollectionConsent = false)
        
        engine.recordInteraction("Happy", 1.0f)
        
        // Profile should remain empty
        assertEquals(emptySet<String>(), engine.userProfile.value.goals)
    }

    @Test
    fun `recordInteraction should update profile when consent is true`() = runTest {
        userSettingsFlow.value = UserSettings(dataCollectionConsent = true)
        
        // Wait for stateIn to process the new value
        advanceUntilIdle()
        
        engine.recordInteraction("Happy", 1.0f)
        
        // Wait for memory update
        advanceUntilIdle()
        
        assertTrue("Profile should contain Happy. Current goals: ${engine.userProfile.value.goals}", 
            engine.userProfile.value.goals.contains("Happy"))
    }

    @Test
    fun `revoking consent should clear local data`() = runTest {
        // 1. Give consent and record something
        userSettingsFlow.value = UserSettings(dataCollectionConsent = true)
        advanceUntilIdle()
        
        engine.recordInteraction("Sad", 1.0f)
        advanceUntilIdle()
        assertTrue("Initial recording failed", engine.userProfile.value.goals.contains("Sad"))

        // 2. Revoke consent
        userSettingsFlow.value = UserSettings(dataCollectionConsent = false)
        
        // 3. Verify profile is cleared via init block collector
        advanceUntilIdle()
        
        assertTrue("Profile should be empty after revoking consent. Current: ${engine.userProfile.value.goals}", 
            engine.userProfile.value.goals.isEmpty())
    }

    @Test
    fun `scoreContent should return neutral when consent is false`() = runTest {
        userSettingsFlow.value = UserSettings(dataCollectionConsent = false)
        val score = engine.scoreContent("Joy", UserPreferenceProfile(dominantEmotion = "Joy"))
        assertEquals(0.5, score, 0.001)
    }
}
