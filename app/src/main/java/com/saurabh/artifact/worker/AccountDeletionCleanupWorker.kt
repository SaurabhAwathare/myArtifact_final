package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.repository.MaintenanceRepository
import com.saurabh.artifact.domain.auth.CleanupStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Authoritative worker responsible for purging all local application data
 * associated with a deleted account. Used both for immediate cleanup
 * and for recovery after interrupted deletions.
 */
class AccountDeletionCleanupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AccountDeletionCleanupEntryPoint {
        fun logoutCoordinator(): LogoutCoordinator
        fun maintenanceRepository(): MaintenanceRepository
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AccountDeletionCleanupEntryPoint::class.java
        )
        
        val logoutCoordinator = entryPoint.logoutCoordinator()
        val maintenanceRepository = entryPoint.maintenanceRepository()

        return try {
            val result = logoutCoordinator.performFullCleanup()
            
            if (result.status == CleanupStatus.COMPLETED) {
                maintenanceRepository.setPendingDeletion(null)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
