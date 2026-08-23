package com.saurabh.artifact.domain.auth

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.util.OnboardingManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountBoundaryLeakTest {

    private lateinit var context: Context
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var onboardingManager: OnboardingManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        onboardingManager = OnboardingManager(context)
    }

    @After
    fun teardown() {
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `isMnemonicSaved should be false for a new device session`() = runTest {
        assertFalse(onboardingManager.isMnemonicSaved.first())
    }

    @Test
    fun `isMnemonicSaved persists across sessions but should be cleared on logout`() = runTest {
        // 1. User A sets up mnemonic
        onboardingManager.setMnemonicSaved(true)
        assertTrue(onboardingManager.isMnemonicSaved.first())
        
        // 2. Simulate Logout
        onboardingManager.clear()
        
        // 3. Verify User B starts fresh
        assertFalse(onboardingManager.isMnemonicSaved.first())
    }
}
