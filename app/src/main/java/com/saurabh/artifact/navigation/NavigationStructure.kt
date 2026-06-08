package com.saurabh.artifact.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes using Kotlin Serialization.
 */

@Serializable
sealed interface Route

// --- Graphs ---

@Serializable
object AuthGraph

@Serializable
object MainGraph

@Serializable
object RecordingGraph

// --- Auth Routes ---

@Serializable
object Onboarding : Route

@Serializable
object Login : Route

// --- Main / Feed Routes ---

@Serializable
object Home : Route

@Serializable
object Feed : Route

@Serializable
object Notifications : Route

@Serializable
data class Comments(val artifactId: String, val ownerId: String) : Route

// --- Profile Routes ---

@Serializable
data class Profile(val userId: String? = null) : Route

@Serializable
data class ResonanceList(val userId: String, val type: String, val title: String? = "Resonators") : Route

@Serializable
object Settings : Route

@Serializable
object Moderation : Route

@Serializable
object IdentitySelection : Route

@Serializable
object AvatarEditor : Route

@Serializable
object PresenceBuilder : Route

// --- Recording Routes ---

@Serializable
object DraftList : Route

@Serializable
data class DraftEdit(val draftId: String) : Route

@Serializable
data class PreRecordingWarning(val prompt: String? = null) : Route

@Serializable
data class InstantRecord(val prompt: String? = null) : Route

@Serializable
data class PostRecordingDecision(val draftId: String) : Route

@Serializable
data class RecordingReview(val draftId: String) : Route

@Serializable
data class PublishApproval(val draftId: String) : Route

@Serializable
data class PublishPreparation(val draftId: String) : Route
