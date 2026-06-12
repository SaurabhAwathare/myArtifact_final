package com.saurabh.artifact.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * Utility for creating standard CoroutineExceptionHandlers that integrate
 * with ArtifactLogger and Firebase Crashlytics.
 */
object CoroutineExceptionHandlerUtils {
    /**
     * Creates a CoroutineExceptionHandler that logs the error via ArtifactLogger.
     * 
     * @param tag The tag for logging.
     * @param message The message describing the context of the error.
     * @param onException Optional callback to perform additional cleanup or state updates.
     */
    fun create(
        tag: String, 
        message: String, 
        onException: ((Throwable) -> Unit)? = null
    ): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            ArtifactLogger.e(tag, message, throwable)
            onException?.invoke(throwable)
        }
    }
}
