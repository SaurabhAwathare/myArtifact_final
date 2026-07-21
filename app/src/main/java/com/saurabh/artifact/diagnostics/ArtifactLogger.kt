package com.saurabh.artifact.diagnostics

import com.saurabh.artifact.BuildConfig

/**
 * Centralized logging utility for the Artifact application.
 * Bridges simple logging calls to the structured [DiagnosticLogger].
 */
object ArtifactLogger {
    private var logger: DiagnosticLogger? = null

    /**
     * Initializes the logger. Should be called in Application.onCreate().
     */
    fun init(diagnosticLogger: DiagnosticLogger) {
        this.logger = diagnosticLogger
    }

    /**
     * Verbose log - only in debug builds.
     */
    fun v(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap()) {
        if (BuildConfig.DEBUG) {
            logger?.log(category, eventName, DiagnosticLogger.Level.TRACE, metadata)
        }
    }

    /**
     * Debug log - only in debug builds.
     */
    fun d(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap()) {
        if (BuildConfig.DEBUG) {
            logger?.log(category, eventName, DiagnosticLogger.Level.DEBUG, metadata)
        }
    }

    /**
     * Info log - logged in both debug and release.
     */
    fun i(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap()) {
        logger?.log(category, eventName, DiagnosticLogger.Level.INFO, metadata)
    }

    /**
     * Warning log - logged in both debug and release.
     */
    fun w(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        logger?.log(category, eventName, DiagnosticLogger.Level.WARN, metadata, throwable)
    }

    /**
     * Error log - logged in both debug and release.
     */
    fun e(category: DiagnosticCategory, eventName: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        logger?.log(category, eventName, DiagnosticLogger.Level.ERROR, metadata, throwable)
    }

    /**
     * Traces the start of a workflow.
     */
    fun start(category: DiagnosticCategory, workflowName: String, metadata: Map<String, Any> = emptyMap()) {
        i(category, "${workflowName}_STARTED", metadata)
    }

    /**
     * Traces the successful completion of a workflow.
     */
    fun end(category: DiagnosticCategory, workflowName: String, metadata: Map<String, Any> = emptyMap()) {
        i(category, "${workflowName}_SUCCESS", metadata)
    }

    /**
     * Logs a structured interaction event for sync workers.
     */
    fun logInteraction(
        interaction: com.saurabh.artifact.data.local.PendingInteractionEntity,
        status: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        val metadata = mapOf(
            "correlation_id" to interaction.correlationId,
            "interaction_id" to interaction.id,
            "interaction_type" to interaction.interactionType,
            "action" to interaction.action,
            LogKeys.ARTIFACT_ID to interaction.artifactId,
            "status" to status,
            LogKeys.RETRY_COUNT to interaction.retryCount,
            LogKeys.WORKER_ID to (interaction.workerId ?: "unknown")
        ) + details.filterValues { it != null }.mapValues { it.value!! }
        
        i(DiagnosticCategory.SYNC, "INTERACTION_SYNC_EVENT", metadata)
    }
}
