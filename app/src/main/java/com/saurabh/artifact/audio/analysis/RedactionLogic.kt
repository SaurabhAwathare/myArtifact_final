package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.SensitiveInfo
import java.util.regex.Pattern

/**
 * Logic for detecting and suggesting redaction for sensitive information in transcripts.
 */
object RedactionLogic {

    private val PHONE_PATTERN = Pattern.compile("(\\+\\d{1,2}\\s)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}")
    private val EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}")

    fun scanForSensitiveInfo(text: String): List<SensitiveInfo> {
        val results = mutableListOf<SensitiveInfo>()
        
        // Scan for Phone Numbers
        val phoneMatcher = PHONE_PATTERN.matcher(text)
        while (phoneMatcher.find()) {
            results.add(SensitiveInfo(
                type = "PHONE_NUMBER",
                originalText = phoneMatcher.group(),
                startChar = phoneMatcher.start(),
                endChar = phoneMatcher.end(),
                confidence = 0.95f
            ))
        }

        // Scan for Emails
        val emailMatcher = EMAIL_PATTERN.matcher(text)
        while (emailMatcher.find()) {
            results.add(SensitiveInfo(
                type = "EMAIL",
                originalText = emailMatcher.group(),
                startChar = emailMatcher.start(),
                endChar = emailMatcher.end(),
                confidence = 0.98f
            ))
        }

        return results
    }

    /**
     * Replaces sensitive portions of the text with redaction markers.
     */
    fun redactText(text: String, sensitiveInfo: List<SensitiveInfo>): String {
        var redactedText = text
        // Sort descending by startChar to avoid index shifting during replacement
        sensitiveInfo.sortedByDescending { it.startChar }.forEach { info ->
            val replacement = "[REDACTED ${info.type}]"
            redactedText = redactedText.substring(0, info.startChar) + 
                           replacement + 
                           redactedText.substring(info.endChar)
        }
        return redactedText
    }
}
