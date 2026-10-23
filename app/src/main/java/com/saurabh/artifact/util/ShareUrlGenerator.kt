package com.saurabh.artifact.util

import com.saurabh.artifact.BuildConfig

/**
 * Generates canonical URLs for sharing artifacts.
 * Centralizes the link structure to ensure consistency across the app and App Links verification.
 */
object ShareUrlGenerator {
    /**
     * The primary domain verified for Android App Links.
     */
    private val BASE_URL = "https://${BuildConfig.FIREBASE_PROJECT_ID}.web.app/a/"

    /**
     * Produces a clickable destination URL for a given artifact ID.
     * The resulting URL is compatible with the intent filters in AndroidManifest.xml.
     */
    fun generateArtifactUrl(artifactId: String): String {
        return "$BASE_URL$artifactId"
    }
}
