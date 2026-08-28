package com.saurabh.artifact.util

import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Utility for creating standard CoroutineExceptionHandlers that integrate
 * with ArtifactLogger and Firebase Crashlytics.
 */
object CoroutineExceptionHandlerUtils {
    /**
     * Creates a CoroutineExceptionHandler that logs the error via ArtifactLogger.
     * 
     * @param category The diagnostic category.
     * @param eventName The name of the event.
     * @param onException Optional callback to perform additional cleanup or state updates.
     */
    fun create(
        category: DiagnosticCategory,
        eventName: String,
        onException: ((Throwable) -> Unit)? = null
    ): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            ArtifactLogger.e(category, eventName, throwable = throwable)
            onException?.invoke(throwable)
        }
    }
}
