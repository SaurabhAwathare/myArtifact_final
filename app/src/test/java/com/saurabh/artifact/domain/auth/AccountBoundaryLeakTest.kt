package com.saurabh.artifact.domain.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.util.OnboardingManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountBoundaryLeakTest {

    private lateinit var context: Context
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
        assertFalse(onboardingManager.isMnemonicSaved("new_user").first())
    }

    @Test
    fun `isMnemonicSaved is scoped by UID and persists across logouts without leaking to new accounts`() = runTest {
        // 1. User A sets up mnemonic
        onboardingManager.setMnemonicSaved("user_A", true)
        assertTrue(onboardingManager.isMnemonicSaved("user_A").first())
        
        // 2. Simulate Logout
        onboardingManager.clearUserSessionData()
        
        // 3. User A recovery status is preserved across logout
        assertTrue(onboardingManager.isMnemonicSaved("user_A").first())

        // 4. User B (new account) starts fresh without recovery setup
        assertFalse(onboardingManager.isMnemonicSaved("user_B").first())
    }
}
