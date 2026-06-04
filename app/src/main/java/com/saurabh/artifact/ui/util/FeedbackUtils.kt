package com.saurabh.artifact.ui.util

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Consistent feedback for disabled or unavailable actions.
 * Combines a subtle haptic pulse with a brief visual explanation.
 */
object FeedbackUtils {
    fun explainDisabledAction(
        context: Context,
        haptic: HapticFeedback,
        reason: String,
    ) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
    }
}
