package com.saurabh.artifact.diagnostics

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.saurabh.artifact.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
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

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(
        category: DiagnosticCategory,
        eventName: String,
        level: DiagnosticLogger.Level,
        metadata: Map<String, Any>,
        throwable: Throwable?
    ) {
        if (!shouldLog(level)) return

        val event = DiagnosticEvent(
            category = category,
            eventName = eventName,
            metadata = metadata,
            sessionId = sessionManager.sessionId
        )

        val logMessage = formatLog(event, throwable)
        val tag = "Artifact_${category.name}"

        // Always log to Logcat based on levels
        when (level) {
            DiagnosticLogger.Level.TRACE -> Log.v(tag, logMessage)
            DiagnosticLogger.Level.DEBUG -> Log.d(tag, logMessage)
            DiagnosticLogger.Level.INFO -> Log.i(tag, logMessage)
            DiagnosticLogger.Level.WARN -> Log.w(tag, logMessage, throwable)
            DiagnosticLogger.Level.ERROR -> Log.e(tag, logMessage, throwable)
        }

        // Route to Crashlytics in non-debug builds
        if (!BuildConfig.DEBUG) {
            recordToCrashlytics(event, level, logMessage, throwable)
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
        val time = dateFormat.format(Date(event.timestamp))
        val thread = event.threadName
        val session = event.sessionId ?: "NO_SESSION"
        
        val metaString = if (event.metadata.isNotEmpty()) {
            "\n${event.metadata.entries.joinToString("\n") { "  ${it.key}=${it.value}" }}"
        } else ""

        val errorString = throwable?.let { 
            "\n  Exception: ${it.javaClass.simpleName}: ${it.message}"
        } ?: ""

        return """
            [$time] [$thread] [Session:$session]
            ${event.eventName}$metaString$errorString
        """.trimIndent()
    }
}
