package com.saurabh.artifact.util

import android.content.res.Configuration
import androidx.core.os.ConfigurationCompat

object TimeUtils {
    /**
     * Formats seconds into MM:SS format using the provided configuration for locale.
     */
    fun formatDuration(seconds: Long, configuration: Configuration): String {
        val locale = ConfigurationCompat.getLocales(configuration)[0] ?: configuration.locales[0]
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(locale, "%02d:%02d", mins, secs)
    }

    /**
     * Formats milliseconds into MM:SS format using the provided configuration for locale.
     */
    fun formatDurationMillis(millis: Long, configuration: Configuration): String {
        return formatDuration(millis / 1000, configuration)
    }

    /**
     * Returns a simple relative time string (e.g., "2m ago", "1h ago").
     */
    fun getRelativeTime(timestamp: com.google.firebase.Timestamp): String {
        val now = System.currentTimeMillis()
        val time = timestamp.toDate().time
        val diff = now - time
        
        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${(diff / 60000).coerceAtLeast(1)}m ago"
            diff < 86400000 -> "${(diff / 3600000).coerceAtLeast(1)}h ago"
            else -> "${(diff / 86400000).coerceAtLeast(1)}d ago"
        }
    }
}
