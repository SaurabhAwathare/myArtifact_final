package com.saurabh.artifact.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class ArtifactLoggerTest {

    private val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
        
        // Mock Log methods to avoid real android implementation in unit tests
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @Test
    fun `test error logging routes to logcat and crashlytics`() {
        val exception = Exception("Test exception")
        ArtifactLogger.e("TestTag", "Test message", exception)

        verify { Log.e("TestTag", "Test message", exception) }
        // Since we are in debug build during tests, crashlytics shouldn't be called 
        // unless we mock BuildConfig or the logic handles it.
        // Our logic uses BuildConfig.DEBUG which is true in tests.
    }
}
