package com.saurabh.artifact.util

import com.saurabh.artifact.model.TranscriptSegment

object SrtFormatter {

    /**
     * Converts a list of [TranscriptSegment] into a standard SRT string.
     */
    fun format(segments: List<TranscriptSegment>): String {
        return buildString {
            segments.forEachIndexed { index, segment ->
                append("${index + 1}\n")
                append("${formatTime(segment.startMs)} --> ${formatTime(segment.endMs)}\n")
                append("${segment.text}\n\n")
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    /**
     * Splits a long segment into multiple lines based on character limit.
     */
    fun segmentLine(text: String, maxChars: Int = 42): List<String> {
        if (text.length <= maxChars) return listOf(text)
        
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        
        for (word in words) {
            if (currentLine.length + word.length + 1 <= maxChars) {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(word)
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }
}
