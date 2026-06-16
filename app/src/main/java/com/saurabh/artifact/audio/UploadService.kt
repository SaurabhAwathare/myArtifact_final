package com.saurabh.artifact.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.saurabh.artifact.data.local.UploadOwner
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class UploadService : Service() {

    @Inject lateinit var publishingManager: com.saurabh.artifact.domain.PublishingManager
    @Inject lateinit var draftRepository: DraftRepository
    @Inject lateinit var uploadTaskDao: UploadTaskDao

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var uploadJob: Job? = null
    
    private lateinit var attributionContext: android.content.Context

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        _activeDraftId.value = null
        attributionContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createAttributionContext("data_sync")
        } else {
            this
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val draftId = intent?.getStringExtra(EXTRA_DRAFT_ID)

        if (draftId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_CANCEL) {
            cancelUpload(draftId)
            return START_NOT_STICKY
        }

        startUpload(draftId)
        return START_NOT_STICKY
    }

    private fun startUpload(draftId: String) {
        if (uploadJob?.isActive == true) {
            Log.w("UploadService", "Upload already in progress for $draftId")
            return
        }

        _activeDraftId.value = draftId
        uploadJob = serviceScope.launch {
            // 1. Acquire Ownership (with 10 min timeout threshold)
            val timeoutThreshold = System.currentTimeMillis() - 10 * 60 * 1000L
            val acquired = withContext(Dispatchers.IO) {
                uploadTaskDao.tryAcquireOwnership(draftId, UploadOwner.SERVICE, timeoutThreshold)
            }

            if (!acquired) {
                Log.w("UploadService", "Could not acquire ownership for $draftId (already owned by WORKER or active SERVICE)")
                stopSelf()
                return@launch
            }

            // 2. Start Foreground
            val notification = NotificationHelper.buildUploadProgressNotification(attributionContext, "Preparing...", 0, draftId)
            startForeground(NotificationHelper.UPLOAD_NOTIFICATION_ID, notification)

            try {
                val draft = draftRepository.getDraft(draftId).getOrNull()
                val title = draft?.title ?: "Artifact"

                publishingManager.performPublish(
                    draftId = draftId,
                    onProgress = { transferred, total, _ ->
                        val progress = (transferred * 100 / total).toInt()
                        NotificationHelper.updateUploadProgress(attributionContext, title, progress, draftId)
                    }
                ).onSuccess {
                    NotificationHelper.showUploadSuccessNotification(attributionContext, title)
                }.onFailure { e ->
                    handleFailure(draftId, e as Exception)
                }
            } catch (e: Exception) {
                Log.e("UploadService", "Upload failed for $draftId", e)
                handleFailure(draftId, e)
            } finally {
                withContext(Dispatchers.IO) {
                    uploadTaskDao.releaseOwnership(draftId)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleFailure(draftId: String, e: Exception) {
        val isPermanent = publishingManager.isPermanentError(e)

        serviceScope.launch(Dispatchers.IO) {
            if (isPermanent) {
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(e.message ?: "Permanent upload failure"))
                NotificationHelper.showUploadErrorNotification(this@UploadService, "Permanent failure")
            } else {
                val isNetworkError = publishingManager.isNetworkError(e)
                val nextStatus = if (isNetworkError) SyncStatus.WaitingForNetwork else SyncStatus.Queued
                draftRepository.updateUploadStatus(draftId, nextStatus)
            }
        }
    }

    private fun cancelUpload(draftId: String) {
        uploadJob?.cancel()
        serviceScope.launch(Dispatchers.IO) {
            uploadTaskDao.releaseOwnership(draftId)
            draftRepository.updateUploadStatus(draftId, SyncStatus.Queued)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        _activeDraftId.value = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DRAFT_ID = "extra_draft_id"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _activeDraftId = MutableStateFlow<String?>(null)
        val activeDraftId: StateFlow<String?> = _activeDraftId.asStateFlow()

        fun start(context: Context, draftId: String) {
            val intent = Intent(context, UploadService::class.java).apply {
                putExtra(EXTRA_DRAFT_ID, draftId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
