package com.saurabh.artifact.util

/**
 * Generates canonical URLs for sharing artifacts.
 * Centralizes the link structure to ensure consistency across the app and App Links verification.
 */
object ShareUrlGenerator {
    /**
     * The primary domain verified for Android App Links.
     */
    private const val BASE_URL = "https://myartifact-555e3.web.app/a/"

    /**
     * Produces a clickable destination URL for a given artifact ID.
     * The resulting URL is compatible with the intent filters in AndroidManifest.xml.
     */
    fun generateArtifactUrl(artifactId: String): String {
        return "$BASE_URL$artifactId"
    }
}
