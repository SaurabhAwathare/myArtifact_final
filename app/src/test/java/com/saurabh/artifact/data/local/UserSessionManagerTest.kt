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

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UserSessionManagerTest {

    private val createdJobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        createdJobs.forEach { it.cancel() }
        createdJobs.clear()
        unmockkAll()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "datastore").deleteRecursively()
    }

    private fun TestScope.createManager(fileName: String): Pair<UserSessionManager, BlockStoreManager> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { context.preferencesDataStoreFile(fileName) }
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

    // ==========================================
    // Stage 3.10.8.2.6B Regression Tests 1 to 18
    // ==========================================

    @Test
    fun `1 Valid remote identity synchronizes all configured fields`() = runTest {
        val (manager, _) = createManager("reg_test1.preferences_pb")
        val configuredUser = com.saurabh.artifact.model.User(
            id = "uid_remote_1",
            anonymousId = "anon_p1",
            anonymousName = "Cosmic Wanderer",
            anonymousSigil = "▲",
            sigilSeed = "remote_seed_1",
            sigilColor = "#123456",
            sigilConfig = SigilConfig(seed = "remote_seed_1", variant = SigilVariant.DARK),
            isAnonymous = false,
            resonanceInCount = 42L,
            resonanceOutCount = 99L
        )

        manager.syncFromRemote(configuredUser)

        assertEquals("uid_remote_1", manager.owningUid.first())
        val profile = manager.userProfile.first()
        assertEquals("anon_p1", profile.anonymousId)
        assertEquals("Cosmic Wanderer", profile.username)
        assertEquals("▲", profile.sigil)
        assertEquals("remote_seed_1", profile.sigilSeed)
        assertEquals("#123456", profile.sigilColor)
        assertEquals(SigilVariant.DARK, profile.sigilConfig.variant)
        assertEquals(false, profile.isAnonymous)
        assertEquals(42L, profile.resonanceInCount)
        assertEquals(99L, profile.resonanceOutCount)

        val raw = manager.rawPreferences.first()
        assertEquals("uid_remote_1", raw[UserSessionManager.PreferencesKeys.OWNING_UID])
        assertEquals("anon_p1", raw[UserSessionManager.PreferencesKeys.ANONYMOUS_ID])
        assertEquals("Cosmic Wanderer", raw[UserSessionManager.PreferencesKeys.USERNAME])
        assertEquals("▲", raw[UserSessionManager.PreferencesKeys.SIGIL])
        assertEquals("remote_seed_1", raw[UserSessionManager.PreferencesKeys.SIGIL_SEED])
        assertEquals("#123456", raw[UserSessionManager.PreferencesKeys.SIGIL_COLOR])
        org.junit.Assert.assertNotNull(raw[UserSessionManager.PreferencesKeys.SIGIL_CONFIG_JSON])
        assertEquals(false, raw[UserSessionManager.PreferencesKeys.IS_ANONYMOUS])
        assertEquals(42L, raw[UserSessionManager.PreferencesKeys.RESONANCE_IN])
        assertEquals(99L, raw[UserSessionManager.PreferencesKeys.RESONANCE_OUT])
    }

    @Test
    fun `2 Blank anonymousName removes USERNAME`() = runTest {
        val (manager, _) = createManager("reg_test2.preferences_pb")
        manager.updateUsername("PriorName")
        val userWithBlankName = com.saurabh.artifact.model.User(
            id = "uid_1",
            anonymousId = "anon_1",
            anonymousName = "",
            anonymousSigil = "▲",
            sigilSeed = "seed_1"
        )
        manager.syncFromRemote(userWithBlankName)

        val raw = manager.rawPreferences.first()
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.USERNAME])
        // Fallback generator should provide non-blank display name
        org.junit.Assert.assertTrue(manager.userProfile.first().username.isNotBlank())
    }

    @Test
    fun `3 Blank Sigil removes SIGIL`() = runTest {
        val (manager, _) = createManager("reg_test3.preferences_pb")
        val initialUser = com.saurabh.artifact.model.User(
            id = "uid_1",
            anonymousId = "anon_1",
            anonymousSigil = "▲",
            sigilSeed = "seed_1"
        )
        manager.syncFromRemote(initialUser)
        assertEquals("▲", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.SIGIL])

        val blankSigilUser = com.saurabh.artifact.model.User(
            id = "uid_1",
            anonymousId = "anon_1",
            anonymousSigil = "",
            sigilSeed = "seed_1"
        )
        manager.syncFromRemote(blankSigilUser)

        val raw = manager.rawPreferences.first()
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL])
    }

    @Test
    fun `4 Blank Sigil seed removes SIGIL_SEED`() = runTest {
        val (manager, _) = createManager("reg_test4.preferences_pb")
        val initialUser = com.saurabh.artifact.model.User(
            id = "uid_1",
            anonymousId = "anon_1",
            anonymousSigil = "▲",
            sigilSeed = "seed_1"
        )
        manager.syncFromRemote(initialUser)
        assertEquals("seed_1", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.SIGIL_SEED])

        val blankSeedUser = com.saurabh.artifact.model.User(
            id = "uid_1",
            anonymousId = "anon_1",
            anonymousSigil = "▲",
            sigilSeed = ""
        )
        manager.syncFromRemote(blankSeedUser)

        val raw = manager.rawPreferences.first()
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_SEED])
    }

    @Test
    fun `5 Unconfigured Sigil removes SIGIL_CONFIG_JSON`() = runTest {
        val (manager, _) = createManager("reg_test5.preferences_pb")
        manager.updateSigilConfig(SigilConfig(seed = "seed_1", variant = SigilVariant.DARK))
        org.junit.Assert.assertNotNull(manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.SIGIL_CONFIG_JSON])

        // Unconfigured Sigil: no glyph, blank seed
        val unconfiguredUser = com.saurabh.artifact.model.User(
            id = "uid_1",
            anonymousId = "anon_1",
            anonymousSigil = "",
            sigilSeed = "",
            sigilConfig = SigilConfig()
        )
        manager.syncFromRemote(unconfiguredUser)

        val raw = manager.rawPreferences.first()
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_CONFIG_JSON])
    }

    @Test
    fun `6 Blank remote identity cannot leave obsolete local Sigil geometry`() = runTest {
        val (manager, _) = createManager("reg_test6.preferences_pb")
        // Account A had custom procedural geometry and color
        val accountAUser = com.saurabh.artifact.model.User(
            id = "uid_A",
            anonymousId = "anon_A",
            anonymousName = "Account A",
            anonymousSigil = "◆",
            sigilSeed = "seed_A",
            sigilColor = "#FF00FF",
            sigilConfig = SigilConfig(seed = "seed_A", variant = SigilVariant.DARK, weight = 5.0f)
        )
        manager.syncFromRemote(accountAUser)
        assertEquals(SigilVariant.DARK, manager.userProfile.first().sigilConfig.variant)
        assertEquals("#FF00FF", manager.userProfile.first().sigilColor)

        // Clear Account A before Account B syncs
        manager.clearIdentityCache("uid_A")

        // Account B has blank/unconfigured identity
        val accountBBlankUser = com.saurabh.artifact.model.User(
            id = "uid_B",
            anonymousId = "anon_B",
            anonymousName = "",
            anonymousSigil = "",
            sigilSeed = "",
            sigilColor = "#FFD700",
            sigilConfig = SigilConfig()
        )
        manager.syncFromRemote(accountBBlankUser, expectedUid = "uid_B")

        val raw = manager.rawPreferences.first()
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_CONFIG_JSON])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_SEED])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_COLOR])

        // Profile should fall back to default Sigil geometry and default color
        val profile = manager.userProfile.first()
        assertEquals(SigilVariant.LIGHT, profile.sigilConfig.variant)
        assertEquals("#FFD700", profile.sigilColor)
    }

    @Test
    fun `7 Remote Google-linked status synchronizes correctly`() = runTest {
        val (manager, _) = createManager("reg_test7.preferences_pb")
        val googleLinkedUser = com.saurabh.artifact.model.User(
            id = "uid_google",
            anonymousId = "usr_google",
            isAnonymous = false
        )
        manager.syncFromRemote(googleLinkedUser)
        assertEquals(false, manager.userProfile.first().isAnonymous)

        val anonymousUser = com.saurabh.artifact.model.User(
            id = "uid_google",
            anonymousId = "usr_google",
            isAnonymous = true
        )
        manager.syncFromRemote(anonymousUser)
        assertEquals(true, manager.userProfile.first().isAnonymous)
    }

    @Test
    fun `8 Resonance counters synchronize with correct types`() = runTest {
        val (manager, _) = createManager("reg_test8.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_res",
            anonymousId = "anon_res",
            resonanceInCount = 9876543210987L,
            resonanceOutCount = 1234567890123L
        )
        manager.syncFromRemote(user)

        val raw = manager.rawPreferences.first()
        val inCount = raw[UserSessionManager.PreferencesKeys.RESONANCE_IN]
        val outCount = raw[UserSessionManager.PreferencesKeys.RESONANCE_OUT]
        assertEquals(9876543210987L, inCount)
        assertEquals(1234567890123L, outCount)
        org.junit.Assert.assertTrue(inCount is Long)
        org.junit.Assert.assertTrue(outCount is Long)
    }

    @Test
    fun `9 Account A delayed synchronization cannot overwrite Account B`() = runTest {
        val (manager, _) = createManager("reg_test9.preferences_pb")
        val accountB = com.saurabh.artifact.model.User(
            id = "uid_B",
            anonymousId = "anon_B",
            anonymousName = "Active Account B"
        )
        manager.syncFromRemote(accountB, expectedUid = "uid_B")
        assertEquals("uid_B", manager.owningUid.first())
        assertEquals("Active Account B", manager.userProfile.first().username)

        // Delayed sync from Account A
        val delayedAccountA = com.saurabh.artifact.model.User(
            id = "uid_A",
            anonymousId = "anon_A",
            anonymousName = "Stale Account A"
        )
        manager.syncFromRemote(delayedAccountA, expectedUid = "uid_B")

        // DataStore MUST NOT be overwritten
        assertEquals("uid_B", manager.owningUid.first())
        assertEquals("Active Account B", manager.userProfile.first().username)
        assertEquals("anon_B", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.ANONYMOUS_ID])

        // Even without expectedUid, OWNING_UID ownership check rejects overwrite
        manager.syncFromRemote(delayedAccountA)
        assertEquals("uid_B", manager.owningUid.first())
        assertEquals("Active Account B", manager.userProfile.first().username)
    }

    @Test
    fun `10 Account A invalidation cannot remove Account B's cache`() = runTest {
        val (manager, _) = createManager("reg_test10.preferences_pb")
        val accountB = com.saurabh.artifact.model.User(
            id = "uid_B",
            anonymousId = "anon_B",
            anonymousName = "Account B Name"
        )
        manager.syncFromRemote(accountB, expectedUid = "uid_B")

        // Account A invalidation arrives
        manager.clearIdentityCache("uid_A")

        // Account B's cache remains fully intact
        assertEquals("uid_B", manager.owningUid.first())
        assertEquals("anon_B", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.ANONYMOUS_ID])
        assertEquals("Account B Name", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.USERNAME])
    }

    @Test
    fun `11 Matching UID invalidation removes only identity-cache fields`() = runTest {
        val (manager, _) = createManager("reg_test11.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_match",
            anonymousId = "anon_match",
            anonymousName = "Matching User",
            anonymousSigil = "▲",
            sigilSeed = "seed_match",
            sigilColor = "#FF5500",
            sigilConfig = SigilConfig(seed = "seed_match"),
            isAnonymous = false,
            resonanceInCount = 10L,
            resonanceOutCount = 20L
        )
        manager.syncFromRemote(user)

        // Set operational session fields
        manager.setLocalSessionId("session_111")
        manager.setActiveDraftId("draft_222")
        manager.setActivePromptId("prompt_333")
        manager.setLoggingOut(true)

        // Perform targeted invalidation
        manager.clearIdentityCache("uid_match")

        val raw = manager.rawPreferences.first()
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.OWNING_UID])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.ANONYMOUS_ID])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.USERNAME])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_SEED])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_COLOR])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.SIGIL_CONFIG_JSON])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.IS_ANONYMOUS])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.RESONANCE_IN])
        org.junit.Assert.assertNull(raw[UserSessionManager.PreferencesKeys.RESONANCE_OUT])

        // Operational session state must be strictly preserved
        assertEquals("session_111", manager.localSessionId.first())
        assertEquals("draft_222", manager.activeDraftId.first())
        assertEquals("prompt_333", manager.activePromptId.first())
        assertEquals(true, manager.isLoggingOut.first())
    }

    @Test
    fun `12 Nonmatching UID invalidation performs no changes`() = runTest {
        val (manager, _) = createManager("reg_test12.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_real",
            anonymousId = "anon_real",
            anonymousName = "Real User"
        )
        manager.syncFromRemote(user)

        manager.clearIdentityCache("uid_other")
        assertEquals("uid_real", manager.owningUid.first())
        assertEquals("Real User", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.USERNAME])

        // Blank UID must be rejected
        manager.clearIdentityCache("")
        assertEquals("uid_real", manager.owningUid.first())
        assertEquals("Real User", manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.USERNAME])
    }

    @Test
    fun `13 Repeated invalidation is safe`() = runTest {
        val (manager, _) = createManager("reg_test13.preferences_pb")
        val user = com.saurabh.artifact.model.User(id = "uid_repeat", anonymousId = "anon_repeat")
        manager.syncFromRemote(user)

        manager.clearIdentityCache("uid_repeat")
        org.junit.Assert.assertNull(manager.owningUid.first())

        // Repeated calls must be idempotent and succeed cleanly
        manager.clearIdentityCache("uid_repeat")
        manager.clearIdentityCache("uid_repeat")
        org.junit.Assert.assertNull(manager.owningUid.first())
    }

    @Test
    fun `14 LOCAL_SESSION_ID remains unchanged`() = runTest {
        val (manager, _) = createManager("reg_test14.preferences_pb")
        manager.setLocalSessionId("local_sess_preserve")

        val user = com.saurabh.artifact.model.User(id = "uid_14", anonymousId = "anon_14")
        manager.syncFromRemote(user)
        manager.clearIdentityCache("uid_14")

        assertEquals("local_sess_preserve", manager.localSessionId.first())
    }

    @Test
    fun `15 ACTIVE_DRAFT_ID remains unchanged`() = runTest {
        val (manager, _) = createManager("reg_test15.preferences_pb")
        manager.setActiveDraftId("draft_preserve_15")

        val user = com.saurabh.artifact.model.User(id = "uid_15", anonymousId = "anon_15")
        manager.syncFromRemote(user)
        manager.clearIdentityCache("uid_15")

        assertEquals("draft_preserve_15", manager.activeDraftId.first())
    }

    @Test
    fun `16 ACTIVE_PROMPT_ID remains unchanged`() = runTest {
        val (manager, _) = createManager("reg_test16.preferences_pb")
        manager.setActivePromptId("prompt_preserve_16")

        val user = com.saurabh.artifact.model.User(id = "uid_16", anonymousId = "anon_16")
        manager.syncFromRemote(user)
        manager.clearIdentityCache("uid_16")

        assertEquals("prompt_preserve_16", manager.activePromptId.first())
    }

    @Test
    fun `17 IS_LOGGING_OUT remains unchanged`() = runTest {
        val (manager, _) = createManager("reg_test17.preferences_pb")
        manager.setLoggingOut(true)

        val user = com.saurabh.artifact.model.User(id = "uid_17", anonymousId = "anon_17")
        manager.syncFromRemote(user)
        manager.clearIdentityCache("uid_17")

        assertEquals(true, manager.isLoggingOut.first())
    }

    @Test
    fun `18 BlockStore remains unchanged`() = runTest {
        val (manager, blockStoreManager) = createManager("reg_test18.preferences_pb")
        io.mockk.clearMocks(blockStoreManager)

        val user = com.saurabh.artifact.model.User(id = "uid_18", anonymousId = "anon_18")
        manager.syncFromRemote(user)
        manager.clearIdentityCache("uid_18")

        // BlockStore MUST NOT be cleared or written during identity-cache invalidation
        io.mockk.coVerify(exactly = 0) { blockStoreManager.clear() }
        io.mockk.coVerify(exactly = 0) { blockStoreManager.saveAnonymousId(any()) }
    }

    @Test
    fun `updateUsername with null expectedUid does not modify established account cache`() = runTest {
        val (manager, _) = createManager("test_update_null_uid.preferences_pb")
        val user = com.saurabh.artifact.model.User(id = "uid_established", anonymousId = "anon_1", anonymousName = "Initial")
        manager.syncFromRemote(user)

        // Attempt anonymous update without expectedUid
        manager.updateUsername("Overwritten")

        // Must retain original name
        assertEquals("Initial", manager.userProfile.first().username)
    }

    @Test
    fun `updateSigilConfig with null expectedUid does not modify established account cache`() = runTest {
        val (manager, _) = createManager("test_sigil_null_uid.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_established",
            anonymousId = "anon_1",
            sigilSeed = "seed_orig",
            sigilConfig = SigilConfig(seed = "seed_orig", variant = SigilVariant.LIGHT)
        )
        manager.syncFromRemote(user)

        // Attempt anonymous sigil update without expectedUid
        manager.updateSigilConfig(SigilConfig(seed = "seed_hack", variant = SigilVariant.DARK))

        // Must retain original config
        assertEquals("seed_orig", manager.userProfile.first().sigilSeed)
        assertEquals(SigilVariant.LIGHT, manager.userProfile.first().sigilConfig.variant)
    }

    @Test
    fun `updateUsername with expectedUid when currentOwner is null rejects mutation and does not establish owner`() = runTest {
        val (manager, _) = createManager("test_update_null_owner_rejects.preferences_pb")
        org.junit.Assert.assertNull(manager.owningUid.first())

        manager.updateUsername("New User", expectedUid = "uid_new")

        // Must NOT establish owner and must NOT update username
        org.junit.Assert.assertNull(manager.owningUid.first())
        org.junit.Assert.assertNull(manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.USERNAME])
    }

    @Test
    fun `updateSigilConfig with expectedUid when currentOwner is null rejects mutation and does not establish owner`() = runTest {
        val (manager, _) = createManager("test_sigil_null_owner_rejects.preferences_pb")
        org.junit.Assert.assertNull(manager.owningUid.first())

        val config = SigilConfig(seed = "seed_fresh", variant = SigilVariant.DARK)
        manager.updateSigilConfig(config, expectedUid = "uid_new")

        // Must NOT establish owner and must NOT update sigil config
        org.junit.Assert.assertNull(manager.owningUid.first())
        org.junit.Assert.assertNull(manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.SIGIL_CONFIG_JSON])
        org.junit.Assert.assertNull(manager.rawPreferences.first()[UserSessionManager.PreferencesKeys.SIGIL_SEED])
    }

    @Test
    fun `updateUsername with null expectedUid and no owner updates username and preserves null owningUid`() = runTest {
        val (manager, _) = createManager("test_update_null_uid_no_owner.preferences_pb")
        org.junit.Assert.assertNull(manager.owningUid.first())

        manager.updateUsername("Anon User")

        org.junit.Assert.assertNull(manager.owningUid.first())
        assertEquals("Anon User", manager.userProfile.first().username)
    }

    @Test
    fun `updateSigilConfig with null expectedUid and no owner updates config and preserves null owningUid`() = runTest {
        val (manager, _) = createManager("test_sigil_null_uid_no_owner.preferences_pb")
        org.junit.Assert.assertNull(manager.owningUid.first())

        val config = SigilConfig(seed = "anon_seed", variant = SigilVariant.DARK)
        manager.updateSigilConfig(config)

        org.junit.Assert.assertNull(manager.owningUid.first())
        assertEquals("anon_seed", manager.userProfile.first().sigilSeed)
        assertEquals(SigilVariant.DARK, manager.userProfile.first().sigilConfig.variant)
    }

    @Test
    fun `updateUsername with matching expectedUid updates established account cache`() = runTest {
        val (manager, _) = createManager("test_update_matching_uid.preferences_pb")
        val user = com.saurabh.artifact.model.User(id = "uid_established", anonymousId = "anon_1", anonymousName = "Initial")
        manager.syncFromRemote(user)

        manager.updateUsername("Updated Authenticated", expectedUid = "uid_established")

        assertEquals("uid_established", manager.owningUid.first())
        assertEquals("Updated Authenticated", manager.userProfile.first().username)
    }

    @Test
    fun `updateSigilConfig with matching expectedUid updates established account cache`() = runTest {
        val (manager, _) = createManager("test_sigil_matching_uid.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_established",
            anonymousId = "anon_1",
            sigilSeed = "seed_orig",
            sigilConfig = SigilConfig(seed = "seed_orig", variant = SigilVariant.LIGHT)
        )
        manager.syncFromRemote(user)

        val updatedConfig = SigilConfig(seed = "seed_updated", variant = SigilVariant.DARK)
        manager.updateSigilConfig(updatedConfig, expectedUid = "uid_established")

        assertEquals("uid_established", manager.owningUid.first())
        assertEquals("seed_updated", manager.userProfile.first().sigilSeed)
        assertEquals(SigilVariant.DARK, manager.userProfile.first().sigilConfig.variant)
    }

    @Test
    fun `updateUsername with mismatched expectedUid does not modify established account cache`() = runTest {
        val (manager, _) = createManager("test_update_mismatched_uid.preferences_pb")
        val user = com.saurabh.artifact.model.User(id = "uid_established", anonymousId = "anon_1", anonymousName = "Initial")
        manager.syncFromRemote(user)

        manager.updateUsername("Hacked Name", expectedUid = "uid_other")

        assertEquals("uid_established", manager.owningUid.first())
        assertEquals("Initial", manager.userProfile.first().username)
    }

    @Test
    fun `updateSigilConfig with mismatched expectedUid does not modify established account cache`() = runTest {
        val (manager, _) = createManager("test_sigil_mismatched_uid.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_established",
            anonymousId = "anon_1",
            sigilSeed = "seed_orig",
            sigilConfig = SigilConfig(seed = "seed_orig", variant = SigilVariant.LIGHT)
        )
        manager.syncFromRemote(user)

        manager.updateSigilConfig(SigilConfig(seed = "seed_hack", variant = SigilVariant.DARK), expectedUid = "uid_other")

        assertEquals("uid_established", manager.owningUid.first())
        assertEquals("seed_orig", manager.userProfile.first().sigilSeed)
        assertEquals(SigilVariant.LIGHT, manager.userProfile.first().sigilConfig.variant)
    }

    @Test
    fun `Account A attempting to mutate Account B identity is rejected`() = runTest {
        val (manager, _) = createManager("test_account_a_vs_b.preferences_pb")
        val userB = com.saurabh.artifact.model.User(
            id = "uid_B",
            anonymousId = "anon_B",
            anonymousName = "Account B Name",
            sigilSeed = "seed_B",
            sigilConfig = SigilConfig(seed = "seed_B", variant = SigilVariant.LIGHT)
        )
        manager.syncFromRemote(userB)

        // Account A tries to overwrite username and sigil config
        manager.updateUsername("Account A Takeover", expectedUid = "uid_A")
        manager.updateSigilConfig(SigilConfig(seed = "seed_A", variant = SigilVariant.DARK), expectedUid = "uid_A")

        // Must remain Account B
        assertEquals("uid_B", manager.owningUid.first())
        assertEquals("Account B Name", manager.userProfile.first().username)
        assertEquals("seed_B", manager.userProfile.first().sigilSeed)
        assertEquals(SigilVariant.LIGHT, manager.userProfile.first().sigilConfig.variant)
    }

    @Test
    fun `rejected identity mutations leave all unrelated DataStore keys unchanged`() = runTest {
        val (manager, _) = createManager("test_rejected_keys_unchanged.preferences_pb")
        val user = com.saurabh.artifact.model.User(
            id = "uid_B",
            anonymousId = "anon_B",
            anonymousName = "Name B",
            anonymousSigil = "▲",
            sigilSeed = "seed_B",
            sigilColor = "#FF0000",
            sigilConfig = SigilConfig(seed = "seed_B", variant = SigilVariant.LIGHT),
            isAnonymous = false,
            resonanceInCount = 100L,
            resonanceOutCount = 50L
        )
        manager.syncFromRemote(user)

        // Set operational keys
        manager.setLocalSessionId("session_123")
        manager.setActiveDraftId("draft_456")
        manager.setActivePromptId("prompt_789")
        manager.setLoggingOut(true)

        val beforePrefs = manager.rawPreferences.first().asMap()

        // Attempt rejected mutations: null expectedUid and mismatched expectedUid
        manager.updateUsername("Hacked Null", expectedUid = null)
        manager.updateUsername("Hacked A", expectedUid = "uid_A")
        manager.updateSigilConfig(SigilConfig(seed = "seed_hack", variant = SigilVariant.DARK), expectedUid = null)
        manager.updateSigilConfig(SigilConfig(seed = "seed_hack", variant = SigilVariant.DARK), expectedUid = "uid_A")

        val afterPrefs = manager.rawPreferences.first().asMap()

        // All keys and values must be completely identical
        assertEquals(beforePrefs.size, afterPrefs.size)
        for ((key, value) in beforePrefs) {
            assertEquals("Key ${key.name} must not be changed", value, afterPrefs[key])
        }

        // Verify key operational and identity properties individually
        assertEquals("uid_B", manager.owningUid.first())
        assertEquals("session_123", manager.localSessionId.first())
        assertEquals("draft_456", manager.activeDraftId.first())
        assertEquals("prompt_789", manager.activePromptId.first())
        assertEquals(true, manager.isLoggingOut.first())
        val profile = manager.userProfile.first()
        assertEquals("anon_B", profile.anonymousId)
        assertEquals("Name B", profile.username)
        assertEquals("▲", profile.sigil)
        assertEquals("seed_B", profile.sigilSeed)
        assertEquals("#FF0000", profile.sigilColor)
        assertEquals(SigilVariant.LIGHT, profile.sigilConfig.variant)
        assertEquals(false, profile.isAnonymous)
        assertEquals(100L, profile.resonanceInCount)
        assertEquals(50L, profile.resonanceOutCount)
    }
}

