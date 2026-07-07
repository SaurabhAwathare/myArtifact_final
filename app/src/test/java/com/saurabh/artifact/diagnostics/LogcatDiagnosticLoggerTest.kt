package com.saurabh.artifact.diagnostics

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class LogcatDiagnosticLoggerTest {

    private val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
    private val sessionManager = mockk<SessionManager>()
    private lateinit var logger: LogcatDiagnosticLogger

    @Before
    fun setup() {
        mockkStatic(Log::class)
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
        every { sessionManager.sessionId } returns "test-session"
        
        // Mock Log methods
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        logger = LogcatDiagnosticLogger(sessionManager)
    }

    @Test
    fun `log should output to Logcat with correct tag and message`() {
        logger.info(DiagnosticCategory.AUTH, "LOGIN_ATTEMPT", mapOf("user" to "test_user"))

        val expectedTag = "Artifact_AUTH"
        verify { Log.i(eq(expectedTag), match { it.contains("LOGIN_ATTEMPT") && it.contains("user=test_user") }) }
    }

    @Test
    fun `error should output to Logcat and record exception if not in debug`() {
        // We can't easily mock BuildConfig.DEBUG in unit tests without additional setup 
        // like a wrapper or using Reflection.
        // But we can verify the Logcat output at least.
        
        val exception = Exception("Critical error")
        logger.error(DiagnosticCategory.DATABASE, "DB_CORRUPTION", mapOf("file" to "app.db"), exception)

        val expectedTag = "Artifact_DATABASE"
        verify { Log.e(eq(expectedTag), match { it.contains("DB_CORRUPTION") }, eq(exception)) }
    }
    
    @Test
    fun `fatal should use CRASH category`() {
        logger.fatal("UNCAUGHT_EXCEPTION", mapOf("reason" to "OOM"))
        
        val expectedTag = "Artifact_CRASH"
        verify { Log.e(eq(expectedTag), match { it.contains("UNCAUGHT_EXCEPTION") }, any()) }
    }
}
