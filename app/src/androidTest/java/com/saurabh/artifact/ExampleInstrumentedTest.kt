package com.saurabh.artifact

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented integration test that verifies the app's startup flow and core branding.
 * This test replaces the default template and uses Hilt for dependency injection.
 */
@HiltAndroidTest
class ExampleInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun app_launches_and_shows_branding() {
        // Hilt will inject dependencies before the test starts
        
        // Wait for the app to pass the splash screen and settle on the initial destination.
        // We look for the "myArtifact" brand title which is present on both Feed and Login screens.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText("myArtifact", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Assert that the branding is visible
        composeTestRule.onNode(hasText("myArtifact", substring = true)).assertExists()
    }
}
