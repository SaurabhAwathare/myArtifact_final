package com.saurabh.artifact.security

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@AndroidEntryPoint
class ExportService : Service() {

    @Inject lateinit var dataExportManager: DataExportManager
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var exportJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val outputUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_OUTPUT_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_OUTPUT_URI)
        }

        if (action == ACTION_CANCEL) {
            cancelExport()
            return START_NOT_STICKY
        }

        if (outputUri == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startExport(outputUri)
        return START_NOT_STICKY
    }

    private fun startExport(outputUri: Uri) {
        if (exportJob?.isActive == true) {
            diagnosticLogger.warn(DiagnosticCategory.SETTINGS, "EXPORT_SERVICE_BUSY")
            return
        }

        val userId = authRepository.currentUserId
        if (userId.isEmpty()) {
            diagnosticLogger.error(DiagnosticCategory.SETTINGS, "EXPORT_SERVICE_ABORT_UNAUTHENTICATED")
            stopSelf()
            return
        }

        diagnosticLogger.info(DiagnosticCategory.SETTINGS, "EXPORT_SERVICE_START")
        
        exportJob = serviceScope.launch {
            // 1. Start Foreground
            val cancelIntent = PendingIntent.getService(
                this@ExportService,
                0,
                Intent(this@ExportService, ExportService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationHelper.buildExportProgressNotification(
                this@ExportService,
                "Preparing export...",
                cancelIntent = cancelIntent
            )

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.EXPORT_NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.EXPORT_NOTIFICATION_ID, notification)
            }

            try {
                dataExportManager.exportData(outputUri) { progress ->
                    _exportState.value = progress
                    updateNotification(progress, cancelIntent)
                }.onSuccess {
                    diagnosticLogger.info(DiagnosticCategory.SETTINGS, "EXPORT_SERVICE_SUCCESS")
                    val finalState = _exportState.value
                    val hasOmissions = (finalState as? ExportProgress.Complete)?.hasOmissions ?: false
                    
                    NotificationHelper.showExportResultNotification(
                        this@ExportService,
                        if (hasOmissions) "Export Completed with Omissions" else "Export Complete",
                        if (hasOmissions) "Some recordings could not be retrieved. See manifest.json." else "Your Artifact archive is ready.",
                        isSuccess = true
                    )
                }.onFailure { e ->
                    diagnosticLogger.error(DiagnosticCategory.SETTINGS, "EXPORT_SERVICE_FAILED", throwable = e)
                    NotificationHelper.showExportResultNotification(
                        this@ExportService,
                        "Export Failed",
                        e.message ?: "An unexpected error occurred.",
                        isSuccess = false
                    )
                }
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.SETTINGS, "EXPORT_SERVICE_FATAL", throwable = e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateNotification(progress: ExportProgress, cancelIntent: PendingIntent) {
        val (text, isIndeterminate, percent) = when (progress) {
            is ExportProgress.Starting -> Triple("Starting export...", true, 0)
            is ExportProgress.Profile -> Triple("Fetching profile...", true, 0)
            is ExportProgress.Artifacts -> Triple("Exporting Artifacts (${progress.current}/${progress.total})", false, (progress.current * 100 / progress.total))
            is ExportProgress.Drafts -> Triple("Exporting Drafts (${progress.current}/${progress.total})", false, (progress.current * 100 / progress.total))
            is ExportProgress.Participation -> Triple("Archiving comments...", true, 0)
            is ExportProgress.Resonance -> Triple("Exporting relationships...", true, 0)
            is ExportProgress.Saved -> Triple("Exporting saved content...", true, 0)
            is ExportProgress.Finalizing -> Triple("Finalizing archive...", true, 0)
            else -> return
        }

        NotificationHelper.updateExportProgress(
            this,
            text,
            isIndeterminate,
            percent,
            cancelIntent
        )
    }

    private fun cancelExport() {
        exportJob?.cancel()
        _exportState.value = ExportProgress.Failed("Export cancelled by user")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _exportState.value = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        private val _exportState = MutableStateFlow<ExportProgress?>(null)
        val exportState: StateFlow<ExportProgress?> = _exportState.asStateFlow()

        fun start(context: Context, outputUri: Uri) {
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_OUTPUT_URI, outputUri)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
