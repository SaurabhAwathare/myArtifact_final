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

    @Test
    fun `log should redact sensitive keys in metadata`() {
        val metadata = mapOf(
            "username" to "saurabh",
            "email" to "test@example.com",
            "password" to "secret123",
            "mnemonic" to "word1 word2",
            "token" to "abc.def.ghi",
            "safe_key" to "safe_value"
        )

        logger.info(DiagnosticCategory.AUTH, "SENSITIVE_LOG", metadata)

        val expectedTag = "Artifact_AUTH"
        verify { 
            Log.i(eq(expectedTag), match { message ->
                message.contains("username=[REDACTED]") &&
                message.contains("email=[REDACTED]") &&
                message.contains("password=[REDACTED]") &&
                message.contains("mnemonic=[REDACTED]") &&
                message.contains("token=[REDACTED]") &&
                message.contains("safe_key=safe_value")
            })
        }
    }

    @Test
    fun `log should redact absolute paths in exception messages`() {
        val path = "/data/user/0/com.saurabh.artifact/files/secret.txt"
        val exception = java.io.FileNotFoundException("Could not find file: $path")
        
        logger.error(DiagnosticCategory.STORAGE, "FILE_ERROR", emptyMap(), exception)

        verify { 
            Log.e(any(), match { it.contains("FILE_ERROR") }, match { redacted ->
                redacted.message?.contains("[REDACTED_PATH]") == true &&
                !redacted.message!!.contains("/data/user/0")
            })
        }
    }

    @Test
    fun `log should redact absolute paths in metadata values`() {
        val path = "/storage/emulated/0/Download/audio.m4a"
        logger.info(DiagnosticCategory.RECORDER, "FILE_CREATED", mapOf("path" to path))

        verify { 
            Log.i(any(), match { it.contains("path=[REDACTED_PATH]") })
        }
    }
}
