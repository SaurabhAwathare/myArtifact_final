package com.saurabh.artifact

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

/**
 * Verifies that the OS correctly delivers deep link intents to MainActivity
 * and that the application life cycle handles them without crashing.
 */
@HiltAndroidTest
class DeepLinkEntryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun testDeepLinkEntry_ColdStart() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://myartifact-555e3.web.app/a/testArtifact")
            setPackage("com.saurabh.artifact")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Verification: The activity should launch successfully.
            // Further verification of MainViewModel state would require more complex hilt injection
            // which is out of scope for Phase 1 (OS Entry only).
            scenario.onActivity {
                // Activity reached
            }
        }
    }
}
