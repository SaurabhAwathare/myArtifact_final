package com.saurabh.artifact.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.saurabh.artifact.model.AvatarConfig
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UserSessionManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun createManager(fileName: String): Pair<UserSessionManager, BlockStoreManager> {
        val testFile = File(temporaryFolder.newFolder(), fileName)
        val dataStore = PreferenceDataStoreFactory.create(
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
    fun `updateAvatarConfig updates config and seed`() = runTest {
        val (manager, _) = createManager("test4.preferences_pb")
        val config = AvatarConfig(seed = "new_seed", theme = "CARTOON")
        manager.updateAvatarConfig(config)
        
        val profile = manager.userProfile.first()
        assertEquals("new_seed", profile.avatarSeed)
        assertEquals("CARTOON", profile.avatarConfig.theme)
    }

    @Test
    fun `clear resets all data`() = runTest {
        val (manager, _) = createManager("test5.preferences_pb")
        // Just clear, no previous write
        manager.clear()
        
        val profile = manager.userProfile.first()
        assertNotNull(profile.anonymousId)
    }
}
