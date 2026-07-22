package com.saurabh.artifact.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * A captured diagnostic event for verification in tests.
 */
data class CapturedEvent(
    val category: DiagnosticCategory,
    val eventName: String,
    val level: DiagnosticLogger.Level,
    val metadata: Map<String, Any>,
    val throwable: Throwable?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A thread-safe [DiagnosticLogger] implementation for unit tests.
 * Captures all logged events and provides fluent assertion helpers.
 */
class FakeDiagnosticLogger : DiagnosticLogger {
    private val _events = mutableListOf<CapturedEvent>()

    /**
     * Snapshot of captured events.
     */
    val events: List<CapturedEvent>
        get() = synchronized(_events) { _events.toList() }

    override fun log(
        category: DiagnosticCategory,
        eventName: String,
        level: DiagnosticLogger.Level,
        metadata: Map<String, Any>,
        throwable: Throwable?
    ) {
        synchronized(_events) {
            _events.add(
                CapturedEvent(
                    category = category,
                    eventName = eventName,
                    level = level,
                    metadata = metadata,
                    throwable = throwable
                )
            )
        }
    }

    /**
     * Clears all captured events.
     */
    fun clear() = synchronized(_events) {
        _events.clear()
    }

    /**
     * Finds events matching the given predicate.
     */
    fun findEvents(predicate: (CapturedEvent) -> Boolean): List<CapturedEvent> {
        return events.filter(predicate)
    }

    /**
     * Asserts that at least one event matching the criteria exists.
     */
    fun assertEventExists(
        category: DiagnosticCategory? = null,
        eventName: String? = null,
        level: DiagnosticLogger.Level? = null,
        predicate: ((CapturedEvent) -> Boolean)? = null
    ) {
        val matches = findEvents { event ->
            (category == null || event.category == category) &&
            (eventName == null || event.eventName == eventName) &&
            (level == null || event.level == level) &&
            (predicate == null || predicate(event))
        }

        if (matches.isEmpty()) {
            val criteria = listOfNotNull(
                category?.let { "category=$it" },
                eventName?.let { "eventName=$it" },
                level?.let { "level=$it" }
            ).joinToString(", ")
            fail("Expected event matching [$criteria] but none were found. Captured events: ${events.map { it.eventName }}")
        }
    }

    /**
     * Asserts that no events matching the criteria exist.
     */
    fun assertNoEvent(
        category: DiagnosticCategory? = null,
        eventName: String? = null,
        level: DiagnosticLogger.Level? = null
    ) {
        val matches = findEvents { event ->
            (category == null || event.category == category) &&
            (eventName == null || event.eventName == eventName) &&
            (level == null || event.level == level)
        }

        if (matches.isNotEmpty()) {
            fail("Expected no event matching criteria, but found: ${matches.size}")
        }
    }

    /**
     * Asserts that events occurred in a specific order.
     */
    fun assertEventOrder(vararg expectedEventNames: String) {
        val capturedNames = events.map { it.eventName }
        var lastIndex = -1
        
        for (expected in expectedEventNames) {
            val currentIndex = capturedNames.indexOfFirst { it == expected && capturedNames.indexOf(it) > lastIndex }
            if (currentIndex == -1) {
                fail("Event '$expected' not found in correct order. Sequence: $capturedNames")
            }
            lastIndex = currentIndex
        }
    }
}
