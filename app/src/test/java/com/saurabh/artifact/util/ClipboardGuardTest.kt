package com.saurabh.artifact.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ClipboardGuardTest {

    private lateinit var clipboardGuard: ClipboardGuard
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardGuard = ClipboardGuard()
    }

    @Test
    fun `copySensitive sets clipboard content`() = runTest {
        val label = "Test Label"
        val text = "Sensitive Text"
        
        clipboardGuard.copySensitive(context, label, text)
        
        val clip = clipboardManager.primaryClip
        assertEquals(text, clip?.getItemAt(0)?.text.toString())
        assertEquals(label, clip?.description?.label)
    }

    @Test
    fun `copySensitive clears clipboard after delay`() = runTest {
        val text = "Clear Me"
        val delay = 1000.milliseconds
        
        clipboardGuard.copySensitive(context, "Label", text, autoClearDelay = delay)
        
        // Before delay
        assertEquals(text, clipboardManager.primaryClip?.getItemAt(0)?.text.toString())
        
        // Wait for delay
        // Note: In a real runTest, delay() is virtual. ClipboardGuard uses its own scope
        // so we need to be careful. For this test, we'll assume the internal scope
        // behaves or we might need to inject the dispatcher.
        
        // Since ClipboardGuard uses guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Robolectric's main looper will handle the execution.
        
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(delay.inWholeMilliseconds + 100))
        
        assertNull(clipboardManager.primaryClip)
    }

    @Test
    fun `clearIfMatches does not clear if content changed`() = runTest {
        val originalText = "Original"
        val newText = "New Content"
        val delay = 1000.milliseconds
        
        clipboardGuard.copySensitive(context, "Label", originalText, autoClearDelay = delay)
        
        // Simulate user copying something else before auto-clear
        clipboardManager.setPrimaryClip(ClipData.newPlainText("New Label", newText))
        
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(delay.inWholeMilliseconds + 100))
        
        // Should NOT be cleared because it doesn't match originalText
        assertEquals(newText, clipboardManager.primaryClip?.getItemAt(0)?.text.toString())
    }
    
    // Helper to access ShadowClipboardManager if needed, though Robolectric handles basic ClipboardManager fine.
    private fun shadowOf(looper: android.os.Looper) = org.robolectric.Shadows.shadowOf(looper)
}
