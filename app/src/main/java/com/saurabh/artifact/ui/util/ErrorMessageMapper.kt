package com.saurabh.artifact.ui.util

import com.saurabh.artifact.R
import com.saurabh.artifact.model.AppError

object ErrorMessageMapper {
    fun map(throwable: Throwable): UiText {
        return when (throwable) {
            is AppError.NetworkFailure -> UiText.StringResource(R.string.connection_faded)
            is AppError.PermissionDenied -> UiText.StringResource(R.string.permission_clouded)
            is AppError.Unauthenticated -> UiText.StringResource(R.string.unauthenticated_presence)
            is AppError.UsernameTaken -> UiText.StringResource(R.string.name_already_resonating)
            is AppError.UserNotFound -> UiText.StringResource(R.string.unauthenticated_presence)
            else -> {
                val message = throwable.message ?: ""
                if (message.contains("RECENT_LOGIN_REQUIRED", ignoreCase = true) || 
                    message.contains("reauthenticate", ignoreCase = true)) {
                    UiText.StringResource(R.string.presence_needs_reaffirmation)
                } else {
                    UiText.StringResource(R.string.generic_error)
                }
            }
        }
    }
    
    fun map(message: String): UiText {
        return if (message.contains("RECENT_LOGIN_REQUIRED", ignoreCase = true) || 
            message.contains("reauthenticate", ignoreCase = true)) {
            UiText.StringResource(R.string.presence_needs_reaffirmation)
        } else {
            // If it's a generic message we can't map specifically, 
            // we at least wrap it in DynamicString, but ideally ViewModels 
            // should use R.string directly via UiText.
            UiText.DynamicString(message)
        }
    }
}
