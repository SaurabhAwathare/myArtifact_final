package com.saurabh.artifact.startup

import android.content.Context
import androidx.startup.Initializer
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.saurabh.artifact.BuildConfig

/**
 * Ensures Firebase App Check is initialized at the absolute earliest point
 * in the process lifecycle, before any other Firebase SDK is accessed.
 */
class SecurityInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
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
}
