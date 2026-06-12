package com.saurabh.artifact.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class CoroutineExceptionHandlerUtilsTest {

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
    fun `handler should log exception to ArtifactLogger`() = runBlocking {
        val tag = "TestTag"
        val message = "Test Message"
        val exception = RuntimeException("Boom")
        
        val handler = CoroutineExceptionHandlerUtils.create(tag, message)
        
        // Use a separate scope to test the handler
        val scope = CoroutineScope(Dispatchers.Unconfined + handler)
        
        scope.launch {
            throw exception
        }.join()

        // Verify ArtifactLogger.e was called (which calls Log.e)
        verify { Log.e(tag, message, exception) }
    }

    @Test
    fun `handler should trigger optional callback`() = runBlocking {
        var callbackCalled = false
        val exception = RuntimeException("Boom")
        
        val handler = CoroutineExceptionHandlerUtils.create("Tag", "Msg") {
            callbackCalled = true
        }
        
        val scope = CoroutineScope(Dispatchers.Unconfined + handler)
        
        scope.launch {
            throw exception
        }.join()

        assert(callbackCalled)
    }
}
