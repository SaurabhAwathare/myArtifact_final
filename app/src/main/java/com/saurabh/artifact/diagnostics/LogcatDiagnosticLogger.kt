package com.saurabh.artifact.diagnostics

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.saurabh.artifact.BuildConfig
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [DiagnosticLogger] that outputs to Android Logcat.
 */
@Singleton
class LogcatDiagnosticLogger @Inject constructor(
    private val sessionManager: SessionManager
) : DiagnosticLogger {

    override fun log(
        category: DiagnosticCategory,
        eventName: String,
        level: DiagnosticLogger.Level,
        metadata: Map<String, Any>,
        throwable: Throwable?
    ) {
        if (!shouldLog(level)) return

        // 1. Sanitize input at the entry point to ensure all downstream consumers are safe.
        val sanitizedMetadata = PrivacyScrubber.sanitizeMetadata(metadata)
        val sanitizedThrowable = PrivacyScrubber.sanitizeThrowable(throwable)

        val event = DiagnosticEvent(
            category = category,
            eventName = eventName,
            metadata = sanitizedMetadata,
            sessionId = sessionManager.sessionId
        )

        val logMessage = formatLog(event, sanitizedThrowable)
        val tag = "Artifact_${category.name}"

        // Always log to Logcat based on levels
        when (level) {
            DiagnosticLogger.Level.TRACE -> Log.v(tag, logMessage)
            DiagnosticLogger.Level.DEBUG -> Log.d(tag, logMessage)
            DiagnosticLogger.Level.INFO -> Log.i(tag, logMessage)
            DiagnosticLogger.Level.WARN -> Log.w(tag, logMessage, sanitizedThrowable)
            DiagnosticLogger.Level.ERROR -> Log.e(tag, logMessage, sanitizedThrowable)
        }

        // Route to Crashlytics in non-debug builds
        if (!BuildConfig.DEBUG) {
            recordToCrashlytics(event, level, logMessage, sanitizedThrowable)
        }
    }

    private fun recordToCrashlytics(
        event: DiagnosticEvent,
        level: DiagnosticLogger.Level,
        logMessage: String,
        throwable: Throwable?
    ) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        
        // Log the message to Crashlytics log buffer
        crashlytics.log("${level.name}/Artifact_${event.category.name}: $logMessage")

        // Record exceptions for WARN and ERROR levels
        if (level >= DiagnosticLogger.Level.WARN) {
            // Set context keys for the next crash/non-fatal
            crashlytics.setCustomKey("last_event_category", event.category.name)
            crashlytics.setCustomKey("last_event_name", event.eventName)
            
            // Metadata is already sanitized from log() entry point
            event.metadata.forEach { (key, value) ->
                crashlytics.setCustomKey("meta_$key", value.toString())
            }

            val exception = throwable ?: Exception("Non-fatal ${level.name}: ${event.eventName}")
            crashlytics.recordException(exception)
        }
    }

    private fun shouldLog(level: DiagnosticLogger.Level): Boolean {
        return if (BuildConfig.DEBUG) {
            true // Log everything in Debug
        } else {
            // In Release, only log INFO and above
            level >= DiagnosticLogger.Level.INFO
        }
    }

    private fun formatLog(event: DiagnosticEvent, throwable: Throwable?): String {
        val session = event.sessionId ?: "---"
        
        val metaString = if (event.metadata.isNotEmpty()) {
            " | ${event.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } else ""

        val errorString = throwable?.let { 
            " | ERROR: ${it.javaClass.simpleName}: ${it.message}"
        } ?: ""

        return "[$session] ${event.eventName}$metaString$errorString"
    }

    /**
     * Internal utility for redacting PII and sensitive data from logs.
     */
    private object PrivacyScrubber {
        private val SENSITIVE_KEYS = setOf(
            "username", "email", "password", "mnemonic", "token", 
            "credential", "passphrase", "secret", "realName", "displayName"
        )
        
        private val PATH_PATTERN = Regex("/(?:data|storage|emulated|mnt)/[^ |]+")

        fun sanitizeMetadata(metadata: Map<String, Any>): Map<String, Any> {
            if (metadata.isEmpty()) return metadata
            return metadata.mapValues { (key, value) ->
                if (SENSITIVE_KEYS.any { key.contains(it, ignoreCase = true) }) {
                    "[REDACTED]"
                } else {
                    // Also check if the value itself looks like a path
                    val stringValue = value.toString()
                    if (stringValue.contains("/") && PATH_PATTERN.containsMatchIn(stringValue)) {
                        stringValue.replace(PATH_PATTERN, "[REDACTED_PATH]")
                    } else {
                        value
                    }
                }
            }
        }

        fun sanitizeThrowable(throwable: Throwable?): Throwable? {
            if (throwable == null) return null
            val message = throwable.message ?: return throwable
            val sanitizedMessage = message.replace(PATH_PATTERN, "[REDACTED_PATH]")
            
            if (message == sanitizedMessage) return throwable
            
            return RedactedException(throwable.javaClass.simpleName, sanitizedMessage, throwable)
        }

        private class RedactedException(
            className: String,
            message: String,
            private val originalCause: Throwable
        ) : Exception("[$className] $message", originalCause) {
            override fun getStackTrace(): Array<StackTraceElement> = originalCause.stackTrace
        }
    }
}
