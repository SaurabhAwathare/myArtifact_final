package com.saurabh.artifact.diagnostics

/**
 * Represents a structured application event for diagnostics.
 *
 * @property category The functional area of the event (e.g., AUTH, PLAYER).
 * @property eventName A unique identifier for the event (e.g., LOGIN_SUCCESS).
 * @property metadata Optional key-value pairs for additional context.
 * @property timestamp Epoch time when the event occurred.
 * @property threadName Name of the thread where the event was recorded.
 * @property sessionId Unique identifier for the current app session.
 */
data class DiagnosticEvent(
    val category: DiagnosticCategory,
    val eventName: String,
    val metadata: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val threadName: String = Thread.currentThread().name,
    val sessionId: String? = null
)
