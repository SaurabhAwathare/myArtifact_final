package com.saurabh.artifact.diagnostics

/**
 * Interface for application-wide diagnostics logging.
 */
interface DiagnosticLogger {
    
    enum class Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    /**
     * Logs a structured diagnostic event.
     */
    fun log(
        category: DiagnosticCategory,
        eventName: String,
        level: Level = Level.INFO,
        metadata: Map<String, Any> = emptyMap(),
        throwable: Throwable? = null
    )

    // Convenience methods
    fun trace(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap()) =
        log(category, eventName, Level.TRACE, metadata)

    fun debug(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap()) =
        log(category, eventName, Level.DEBUG, metadata)

    fun info(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap()) =
        log(category, eventName, Level.INFO, metadata)

    fun warn(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) =
        log(category, eventName, Level.WARN, metadata, throwable)

    fun error(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) =
        log(category, eventName, Level.ERROR, metadata, throwable)

    /**
     * Measures the duration of a block of code and logs it.
     */
    suspend fun <T> measure(
        category: DiagnosticCategory,
        eventName: String,
        metadata: Map<String, Any> = emptyMap(),
        block: suspend () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val duration = System.currentTimeMillis() - start
            info(category, eventName, metadata + ("durationMs" to duration))
        }
    }
}
