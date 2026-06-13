package com.saurabh.artifact.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.saurabh.artifact.BuildConfig

/**
 * Centralized logging utility that routes logs to Android Logcat and Firebase Crashlytics.
 * Errors and Warnings are automatically recorded as non-fatal exceptions in Crashlytics
 * when running in non-debug builds.
 */
object ArtifactLogger {

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        if (!BuildConfig.DEBUG) {
            FirebaseCrashlytics.getInstance().log("D/$tag: $message")
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        if (!BuildConfig.DEBUG) {
            FirebaseCrashlytics.getInstance().log("I/$tag: $message")
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        if (!BuildConfig.DEBUG) {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.log("W/$tag: $message")
            throwable?.let { crashlytics.recordException(it) }
                ?: crashlytics.recordException(Exception("Non-fatal Warning: $message"))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (!BuildConfig.DEBUG) {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.log("E/$tag: $message")
            throwable?.let { crashlytics.recordException(it) }
                ?: crashlytics.recordException(Exception("Non-fatal Error: $message"))
        }
    }
}
