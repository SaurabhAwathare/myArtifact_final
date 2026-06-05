package com.saurabh.artifact.ui.util

import com.saurabh.artifact.R
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.ReactionType

object NotificationMapper {

    fun mapToUiText(notification: NotificationItem): UiText {
        val parts = notification.message.split("|")
        val key = parts[0]

        return when (key) {
            "REPLY_RECEIVED" -> UiText.StringResource(R.string.notification_reply_received)
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
            "PRESENCE_RESONATED" -> UiText.StringResource(R.string.notification_presence_resonated)
            "AVATAR_UPDATED" -> UiText.StringResource(R.string.notification_avatar_updated)
            "REFLECTION_ARRIVAL" -> {
                val title = parts.getOrNull(1) ?: ""
                UiText.StringResource(R.string.notification_reflection_arrival, title)
            }
            "REFLECTION_ARRIVAL_GENERIC" -> UiText.StringResource(R.string.notification_reflection_arrival_generic)
            "RESONANCE" -> {
                val typeId = parts.getOrNull(1) ?: ""
                val type = ReactionType.fromId(typeId)
                UiText.DynamicString("${type.atmosphericLabel} ${type.emoji}")
            }
            else -> UiText.DynamicString(notification.message)
        }
    }
}
