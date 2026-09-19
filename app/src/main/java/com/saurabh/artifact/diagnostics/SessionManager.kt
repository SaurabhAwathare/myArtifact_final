package com.saurabh.artifact.diagnostics

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the current application session ID.
 */
@Singleton
class SessionManager @Inject constructor() {
    var sessionId: String = generateSessionId()
        private set

    fun rotateSession() {
        sessionId = generateSessionId()
    }

    private fun generateSessionId(): String = UUID.randomUUID().toString().take(8).uppercase()
}
