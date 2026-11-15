package com.saurabh.artifact.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.ui.graphics.vector.ImageVector
import com.saurabh.artifact.R
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.NotificationType
import com.saurabh.artifact.model.ReactionType

data class NotificationPresentation(
    val title: UiText,
    val subtitle: UiText,
    val type: NotificationType,
    val eventIcon: ImageVector,
    val eventDescription: String,
    val contextArtifactTitle: String? = null
)

object NotificationMapper {

    fun mapToPresentation(
        notification: NotificationItem,
        actorName: String? = null
    ): NotificationPresentation {
        val parts = notification.message.split("|")
        val key = parts[0]

        val effectiveType = when {
            notification.type == NotificationType.COMMENT || key == "COMMENT" -> NotificationType.COMMENT
            notification.type == NotificationType.FOLLOW || key == "PRESENCE_RESONATED" -> NotificationType.FOLLOW
            notification.type == NotificationType.RESONANCE || key == "RESONANCE" -> NotificationType.RESONANCE
            else -> NotificationType.SYSTEM
        }

        return when (effectiveType) {
            NotificationType.COMMENT -> {
                val artifactTitle = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                val title = if (!actorName.isNullOrBlank()) {
                    UiText.DynamicString(actorName)
                } else {
                    UiText.StringResource(R.string.notification_anonymous_actor)
                }
                val subtitle = if (artifactTitle != null) {
                    UiText.StringResource(R.string.notification_comment_desc, artifactTitle)
                } else {
                    UiText.StringResource(R.string.notification_comment_desc_generic)
                }
                NotificationPresentation(
                    title = title,
                    subtitle = subtitle,
                    type = NotificationType.COMMENT,
                    eventIcon = Icons.Rounded.ChatBubbleOutline,
                    eventDescription = "Comment",
                    contextArtifactTitle = artifactTitle
                )
            }
            NotificationType.RESONANCE -> {
                val typeId = if (key == "RESONANCE") parts.getOrNull(1) ?: "" else ""
                val reactionType = ReactionType.fromId(typeId)
                val title = if (!actorName.isNullOrBlank()) {
                    UiText.DynamicString(actorName)
                } else {
                    UiText.StringResource(R.string.notification_anonymous_actor)
                }
                val subtitle = if (!actorName.isNullOrBlank()) {
                    val label = reactionType.atmosphericLabel.replace("^Someone\\s+".toRegex(RegexOption.IGNORE_CASE), "")
                    UiText.DynamicString("${label.replaceFirstChar { it.uppercase() }} ${reactionType.emoji}")
                } else {
                    UiText.DynamicString("${reactionType.atmosphericLabel} ${reactionType.emoji}")
                }
                NotificationPresentation(
                    title = title,
                    subtitle = subtitle,
                    type = NotificationType.RESONANCE,
                    eventIcon = Icons.Rounded.GraphicEq,
                    eventDescription = "Resonance"
                )
            }
            NotificationType.FOLLOW -> {
                val title = if (!actorName.isNullOrBlank()) {
                    UiText.DynamicString(actorName)
                } else {
                    UiText.StringResource(R.string.notification_anonymous_actor)
                }
                NotificationPresentation(
                    title = title,
                    subtitle = UiText.StringResource(R.string.notification_follow_desc),
                    type = NotificationType.FOLLOW,
                    eventIcon = Icons.Rounded.PersonAdd,
                    eventDescription = "Follow"
                )
            }
            NotificationType.SYSTEM -> {
                when (key) {
                    "NEW_ARTIFACT" -> {
                        val title = parts.getOrNull(1) ?: ""
                        NotificationPresentation(
                            title = UiText.StringResource(R.string.notification_system_new_artifact_title),
                            subtitle = UiText.StringResource(R.string.notification_new_artifact, title),
                            type = NotificationType.SYSTEM,
                            eventIcon = Icons.Rounded.GraphicEq,
                            eventDescription = "Artifact Published",
                            contextArtifactTitle = title
                        )
                    }
                    "IDENTITY_REFRESHED" -> {
                        val name = parts.getOrNull(1) ?: ""
                        val sigil = parts.getOrNull(2) ?: ""
                        NotificationPresentation(
                            title = UiText.StringResource(R.string.notification_system_identity_title),
                            subtitle = UiText.StringResource(R.string.notification_identity_refreshed, name, sigil),
                            type = NotificationType.SYSTEM,
                            eventIcon = Icons.Rounded.AutoAwesome,
                            eventDescription = "Identity Refreshed"
                        )
                    }
                    "USERNAME_UPDATED" -> {
                        val username = parts.getOrNull(1) ?: ""
                        NotificationPresentation(
                            title = UiText.StringResource(R.string.notification_system_profile_title),
                            subtitle = UiText.StringResource(R.string.notification_username_updated, username),
                            type = NotificationType.SYSTEM,
                            eventIcon = Icons.Rounded.Edit,
                            eventDescription = "Profile Updated"
                        )
                    }
                    "AVATAR_UPDATED", "SIGIL_UPDATED" -> {
                        NotificationPresentation(
                            title = UiText.StringResource(R.string.notification_system_sigil_title),
                            subtitle = UiText.StringResource(R.string.notification_sigil_updated),
                            type = NotificationType.SYSTEM,
                            eventIcon = Icons.Rounded.Palette,
                            eventDescription = "Sigil Updated"
                        )
                    }
                    else -> {
                        NotificationPresentation(
                            title = UiText.StringResource(R.string.notification_system_title),
                            subtitle = UiText.DynamicString(notification.message),
                            type = NotificationType.SYSTEM,
                            eventIcon = Icons.Rounded.Info,
                            eventDescription = "System Notice"
                        )
                    }
                }
            }
        }
    }

    fun mapToUiText(notification: NotificationItem, actorName: String? = null): UiText {
        val parts = notification.message.split("|")
        val key = parts[0]

        return when (key) {
            "NEW_ARTIFACT" -> {
                val title = parts.getOrNull(1) ?: ""
                UiText.StringResource(R.string.notification_new_artifact, title)
            }
            "IDENTITY_REFRESHED" -> {
                val name = parts.getOrNull(1) ?: ""
                val sigil = parts.getOrNull(2) ?: ""
                UiText.StringResource(R.string.notification_identity_refreshed, name, sigil)
            }
            "USERNAME_UPDATED" -> {
                val username = parts.getOrNull(1) ?: ""
                UiText.StringResource(R.string.notification_username_updated, username)
            }
            "PRESENCE_RESONATED" -> {
                if (!actorName.isNullOrBlank()) {
                    UiText.StringResource(R.string.notification_presence_resonated_with_actor, actorName)
                } else {
                    UiText.StringResource(R.string.notification_presence_resonated)
                }
            }
            "AVATAR_UPDATED", "SIGIL_UPDATED" -> UiText.StringResource(R.string.notification_sigil_updated)
            "RESONANCE" -> {
                val typeId = parts.getOrNull(1) ?: ""
                val type = ReactionType.fromId(typeId)
                if (!actorName.isNullOrBlank()) {
                    val label = type.atmosphericLabel.replace("^Someone\\s+".toRegex(RegexOption.IGNORE_CASE), "")
                    UiText.DynamicString("$actorName ${label.lowercase()} ${type.emoji}")
                } else {
                    UiText.DynamicString("${type.atmosphericLabel} ${type.emoji}")
                }
            }
            "COMMENT" -> {
                val title = parts.getOrNull(1) ?: ""
                if (!actorName.isNullOrBlank()) {
                    UiText.StringResource(R.string.notification_comment_received_with_actor, actorName, title)
                } else {
                    UiText.StringResource(R.string.notification_comment_received, title)
                }
            }
            else -> UiText.DynamicString(notification.message)
        }
    }
}
