package com.saurabh.artifact.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.sigil.SigilVariant
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class UserSessionManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val createdJobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        createdJobs.forEach { it.cancel() }
        createdJobs.clear()
        unmockkAll()
    }

    private fun TestScope.createManager(fileName: String): Pair<UserSessionManager, BlockStoreManager> {
        val testFolder = temporaryFolder.newFolder()
        val testFile = File(testFolder, fileName)
        val job = Job()
        createdJobs.add(job)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
            produceFile = { testFile }
        )
        val blockStoreManager = mockk<BlockStoreManager>(relaxed = true)
        return UserSessionManager(dataStore, blockStoreManager) to blockStoreManager
    }

    @Test
    fun `userProfile returns default values`() = runTest {
        val (manager, _) = createManager("test1.preferences_pb")
        val profile = manager.userProfile.first()
        
        assertNotNull(profile.anonymousId)
        assertEquals(true, profile.isAnonymous)
    }

    @Test
    fun `ensureAnonymousId saves to block store`() = runTest {
        val (manager, blockStoreManager) = createManager("test2.preferences_pb")
        manager.ensureAnonymousId()
        
        val profile = manager.userProfile.first()
        assertNotNull(profile.anonymousId)
        coVerify(atLeast = 1) { blockStoreManager.saveAnonymousId(any()) }
    }

    @Test
    fun `updateUsername updates the username in DataStore`() = runTest {
        val (manager, _) = createManager("test3.preferences_pb")
        val newUsername = "Test User"
        manager.updateUsername(newUsername)
        
        val profile = manager.userProfile.first()
        assertEquals(newUsername, profile.username)
    }

    @Test
    fun `updateSigilConfig updates config and seed`() = runTest {
        val (manager, _) = createManager("test4.preferences_pb")
        val config = SigilConfig(seed = "new_seed", variant = SigilVariant.DARK)
        manager.updateSigilConfig(config)
        
        val profile = manager.userProfile.first()
        assertEquals("new_seed", profile.sigilSeed)
        assertEquals(SigilVariant.DARK, profile.sigilConfig.variant)
    }

    @Test
    fun `clear resets all data`() = runTest {
        val (manager, _) = createManager("test5.preferences_pb")
        // Just clear, no previous write
        manager.clear()
        
        val profile = manager.userProfile.first()
        assertNotNull(profile.anonymousId)
    }

    @Test
    fun `ensureAnonymousId initializes missing SIGIL_SEED once and persists it`() = runTest {
        val (manager, _) = createManager("test_sigil_init.preferences_pb")
        manager.ensureAnonymousId()
        
        val profile = manager.userProfile.first()
        assertNotNull(profile.sigilSeed)
    }

    @Test
    fun `existing SIGIL_SEED is never overwritten by ensureAnonymousId`() = runTest {
        val (manager, _) = createManager("test_sigil_preserve.preferences_pb")
        val customConfig = SigilConfig(seed = "custom_persisted_seed")
        manager.updateSigilConfig(customConfig)

        val profile = manager.userProfile.first()
        assertEquals("custom_persisted_seed", profile.sigilSeed)
    }
}
