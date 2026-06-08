package com.saurabh.artifact.util

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for components that can release memory upon request.
 */
interface MemoryTrimable {
    fun trimMemory(level: Int)
}

/**
 * A central coordinator for memory management.
 * Components register themselves to receive notifications about system memory pressure.
 */
@Singleton
class MemoryManager @Inject constructor() {
    private val listeners = mutableSetOf<MemoryTrimable>()

    fun register(listener: MemoryTrimable) {
        listeners.add(listener)
    }

    fun unregister(listener: MemoryTrimable) {
        listeners.remove(listener)
    }

    fun notifyTrim(level: Int) {
        Log.d(TAG, "Notifying ${listeners.size} listeners about trim level: $level")
        listeners.forEach { 
            try {
                it.trimMemory(level)
            } catch (e: Exception) {
                Log.e(TAG, "Error trimming memory for listener", e)
            }
        }
    }

    companion object {
        private const val TAG = "MemoryManager"
    }
}
