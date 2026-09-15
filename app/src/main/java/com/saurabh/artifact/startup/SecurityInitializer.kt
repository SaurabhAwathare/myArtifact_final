package com.saurabh.artifact.startup

import android.content.Context
import androidx.core.content.edit
import androidx.startup.Initializer
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.saurabh.artifact.BuildConfig
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory

/**
 * Ensures Firebase App Check is initialized at the absolute earliest point
 * in the process lifecycle, before any other Firebase SDK is accessed.
 */
class SecurityInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            configureFixedDebugAppCheckSecret(context)
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        } else {
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        fun configureFixedDebugAppCheckSecret(context: Context) {
            if (BuildConfig.DEBUG && BuildConfig.APP_CHECK_DEBUG_SECRET.isNotBlank()) {
                try {
                    val firebaseApp = FirebaseApp.getInstance()
                    val persistenceKey = firebaseApp.persistenceKey
                    val prefsName = "com.google.firebase.appcheck.debug.store.$persistenceKey"
                    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    prefs.edit {
                        putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", BuildConfig.APP_CHECK_DEBUG_SECRET)
                    }
                    ArtifactLogger.i(
                        DiagnosticCategory.SECURITY,
                        "FIXED_APP_CHECK_DEBUG_SECRET_CONFIGURED"
                    )
                } catch (e: Exception) {
                    ArtifactLogger.w(
                        DiagnosticCategory.SECURITY,
                        "FIXED_APP_CHECK_DEBUG_SECRET_FAILED",
                        throwable = e
                    )
                }
            }
        }
    }
}
