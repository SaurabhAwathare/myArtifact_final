package com.saurabh.artifact.startup

import android.content.Context
import androidx.startup.Initializer

/**
 * Empty initializer that acts as a boundary for core dependency availability.
 */
class DependencyGraphInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // No-op. Hilt will handle dependency injection, 
        // but this ensures any content providers Hilt depends on are ready.
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
