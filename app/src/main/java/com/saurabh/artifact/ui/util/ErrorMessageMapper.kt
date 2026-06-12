package com.saurabh.artifact.ui.util

import com.saurabh.artifact.R
import com.saurabh.artifact.model.AppError

object ErrorMessageMapper {
    fun mapToUiError(throwable: Throwable, onRetry: (() -> Unit)? = null): UiError {
        val message = map(throwable)
        val appError = throwable as? AppError
        
        val (actionLabel, action) = when (appError?.recoveryPath) {
            AppError.RecoveryPath.Retry -> {
                if (onRetry != null) UiText.StringResource(R.string.action_retry) to onRetry
                else null to null
            }
            AppError.RecoveryPath.Reauthenticate -> {
                UiText.StringResource(R.string.action_reauthenticate) to {} // Logic for re-auth usually handled globally or via navigation
            }
            AppError.RecoveryPath.Support -> {
                UiText.StringResource(R.string.action_support) to {} // Future: open support mailer
            }
            null -> null to null
        }

        return UiError(
            message = message,
            actionLabel = actionLabel,
            onAction = action
        )
    }

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
