package com.saurabh.artifact.worker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * Schedules a synchronization task to upload pending engagement data.
     * Uses unique work to prevent redundant worker instances while ensuring
     * that at least one sync run eventually processes the latest changes.
     * 
     * Hardened: Now uses the authoritative InteractionSyncWorker.enqueue() to 
     * ensure TAG_USER_SESSION_WORK is applied for reliable logout cancellation.
     */
    fun scheduleSync() {
        InteractionSyncWorker.enqueue(context)
    }
}
