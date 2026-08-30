package com.saurabh.artifact.diagnostics

/**
 * Reserved metadata keys for structured diagnostic logging.
 * Using these keys ensures consistency and searchability across all subsystems.
 */
object LogKeys {
    const val USER_ID = "user_id"
    const val ARTIFACT_ID = "artifact_id"
    const val DRAFT_ID = "draft_id"
    const val PROMPT_ID = "prompt_id"
    
    const val WORKER_ID = "worker_id"
    
    const val DURATION_MS = "duration_ms"
    const val RETRY_COUNT = "retry_count"
    const val ERROR_CODE = "error_code"
    
    const val EXCEPTION_CLASS = "exception_class"
    const val EXCEPTION_MESSAGE = "exception_message"
}

