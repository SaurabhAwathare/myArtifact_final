package com.saurabh.artifact.util

import android.content.res.Configuration
import androidx.core.os.ConfigurationCompat
import java.util.Locale

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
}
