package com.saurabh.artifact

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltTestApplication

/**
 * A custom [AndroidJUnitRunner] that uses [HiltTestApplication] as the application class
 * for instrumented tests that require dependency injection.
 * Initializes WorkManager in [onStart] before background services run.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }

    override fun onStart() {
        try {
            WorkManager.initialize(targetContext, Configuration.Builder().build())
        } catch (_: Exception) {
            // Already initialized
        }
        super.onStart()
    }
}
