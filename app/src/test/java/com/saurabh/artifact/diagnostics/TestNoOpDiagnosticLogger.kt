package com.saurabh.artifact.diagnostics

/**
 * A test-only no-op implementation used for cleaning up [ArtifactLogger] state.
 */
object TestNoOpDiagnosticLogger : DiagnosticLogger {
    override fun log(
        category: DiagnosticCategory,
        eventName: String,
        level: DiagnosticLogger.Level,
        metadata: Map<String, Any>,
        throwable: Throwable?
    ) {
        // No-op
    }
}
