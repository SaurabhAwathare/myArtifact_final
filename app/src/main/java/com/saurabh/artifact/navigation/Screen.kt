package com.saurabh.artifact.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Onboarding : Screen("onboarding")
    object Login : Screen("login")
    object Home : Screen("home")
    object Feed : Screen("feed")
    object Profile : Screen("profile") {
        const val ROUTE_TEMPLATE = "profile?userId={userId}"
        fun createRoute(userId: String? = null): String {
            return if (userId != null) "profile?userId=$userId" else "profile"
        }
    }
    object Settings : Screen("settings")
    object DraftEdit : Screen("draft_edit/{filePath}") {
        fun createRoute(filePath: String): String {
            val encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8.toString())
            return "draft_edit/$encodedPath"
        }
    }
    object Notifications : Screen("notifications")
    object DraftList : Screen("draft_list")
    
    object IdentitySelection : Screen("identity_selection")
    object AvatarCreator : Screen("avatar_creator")

    object PreRecordingWarning : Screen("pre_recording_warning?prompt={prompt}") {
        fun createRoute(prompt: String? = null): String {
            return if (prompt != null) {
                val encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8.toString())
                "pre_recording_warning?prompt=$encodedPrompt"
            } else {
                "pre_recording_warning"
            }
        }
    }

    object InstantRecord : Screen("instant_record?prompt={prompt}") {
        fun createRoute(prompt: String? = null): String {
            return if (prompt != null) {
                val encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8.toString())
                "instant_record?prompt=$encodedPrompt"
            } else {
                "instant_record"
            }
        }
    }

    object Moderation : Screen("moderation")

    object RecordingReview : Screen("recording_review/{draftId}") {
        fun createRoute(draftId: String): String {
            val encodedId = URLEncoder.encode(draftId, StandardCharsets.UTF_8.toString())
            return "recording_review/$encodedId"
        }
    }

    object PublishApproval : Screen("publish_approval/{draftId}") {
        fun createRoute(draftId: String): String {
            val encodedId = URLEncoder.encode(draftId, StandardCharsets.UTF_8.toString())
            return "publish_approval/$encodedId"
        }
    }

    object Comments : Screen("comments/{artifactId}/{ownerId}") {
        fun createRoute(artifactId: String, ownerId: String): String {
            val encodedArtifactId = URLEncoder.encode(artifactId, StandardCharsets.UTF_8.toString())
            val encodedOwnerId = URLEncoder.encode(ownerId, StandardCharsets.UTF_8.toString())
            return "comments/$encodedArtifactId/$encodedOwnerId"
        }
    }
}
