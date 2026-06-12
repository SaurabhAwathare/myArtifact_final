package com.saurabh.artifact.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A security utility that manages sensitive data in the system clipboard.
 * 
 * Features:
 * 1. Mark data as sensitive (Android 13+).
 * 2. Auto-clear clipboard after a specified delay.
 * 3. Only clear if the clipboard still contains the original sensitive data.
 */
@Singleton
class ClipboardGuard @Inject constructor() {

    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clearJob: Job? = null

    /**
     * Copies sensitive text to the clipboard and schedules it for deletion.
     *
     * @param context Application context.
     * @param label A user-visible label for the clip.
     * @param text The sensitive content to copy.
     * @param autoClearDelay Delay before the clipboard is wiped. Defaults to 60 seconds.
     */
    fun copySensitive(
        context: Context,
        label: String,
        text: String,
        autoClearDelay: Duration = 60.seconds
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)

        // Android 13 (API 33) introduced a flag to hide sensitive content from system UI/keyboards
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        clipboard.setPrimaryClip(clip)
        Log.d("ClipboardGuard", "Sensitive data copied to clipboard. Scheduled for clearing in $autoClearDelay.")

        // Cancel any pending clear job to avoid race conditions
        clearJob?.cancel()

        // Schedule automatic clearing
        clearJob = guardScope.launch {
            delay(autoClearDelay)
            clearIfMatches(clipboard, text)
        }
    }

    /**
     * Wipes the clipboard only if it still contains the text we copied.
     * This prevents us from clearing something the user copied later from another app.
     */
    private fun clearIfMatches(clipboard: ClipboardManager, expectedText: String) {
        val currentClip = clipboard.primaryClip
        if (currentClip != null && currentClip.itemCount > 0) {
            val currentText = currentClip.getItemAt(0).text?.toString()
            if (currentText == expectedText) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    // Fallback for older versions: set an empty clip
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                Log.d("ClipboardGuard", "Clipboard cleared automatically by security policy.")
            } else {
                Log.d("ClipboardGuard", "Clipboard clearing skipped: content has changed.")
            }
        }
    }
}
