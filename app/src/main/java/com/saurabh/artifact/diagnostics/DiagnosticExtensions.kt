package com.saurabh.artifact.diagnostics

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * Creates a [CoroutineExceptionHandler] that logs uncaught exceptions to the [DiagnosticLogger].
 * 
 * @param category The category to log the error under.
 * @param eventName The name of the event to log.
 * @param metadata Optional metadata to include in the log.
 */
fun DiagnosticLogger.createExceptionHandler(
    category: DiagnosticCategory = DiagnosticCategory.CRASH,
    eventName: String = "COROUTINE_UNCAUGHT_EXCEPTION",
    metadata: Map<String, Any> = emptyMap()
): CoroutineExceptionHandler {
    return object : CoroutineExceptionHandler {
        override val key: CoroutineContext.Key<*> get() = CoroutineExceptionHandler
        
        override fun handleException(context: CoroutineContext, exception: Throwable) {
            val contextMetadata = metadata + mapOf(
                "coroutine_context" to context.toString(),
                LogKeys.EXCEPTION_CLASS to exception.javaClass.simpleName,
                LogKeys.EXCEPTION_MESSAGE to (exception.message ?: "No message")
            )
            error(category, eventName, contextMetadata, exception)
        }
    }
}

/**
 * Logs a structured interaction event for sync workers.
 */
fun DiagnosticLogger.logInteraction(
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
    
    info(DiagnosticCategory.SYNC, "INTERACTION_SYNC_EVENT", metadata)
}
