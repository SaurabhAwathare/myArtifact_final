package com.saurabh.artifact.diagnostics

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the current application session ID.
 */
@Singleton
class SessionManager @Inject constructor() {
    val sessionId: String = UUID.randomUUID().toString().take(8).uppercase()
}
