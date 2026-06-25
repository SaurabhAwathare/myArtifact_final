package com.saurabh.artifact.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import com.saurabh.artifact.util.ArtifactLogger
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.saurabh.artifact.MainActivity
import com.saurabh.artifact.R
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@AndroidEntryPoint
class RecordingService : Service() {

    private val binder = RecordingBinder()
    @Inject lateinit var publishingOrchestrator: PublishingOrchestrator

    private val serviceScope = CoroutineScope(
        Dispatchers.Main + 
        SupervisorJob() + 
        CoroutineExceptionHandlerUtils.create("RecordingService", "ServiceScope failure")
    )
    
    private var audioRecorder: AudioRecorder? = null
    private var timerJob: Job? = null
    private var pacingJob: Job? = null
    
    private var wasPausedByFocusLoss = false
    
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("RecordingService", "Audio focus lost (permanent). Pausing recording.")
                wasPausedByFocusLoss = false
                pauseRecording()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_recordingState.value.status == RecordingStatus.RECORDING) {
                    Log.d("RecordingService", "Audio focus lost (transient: $focusChange). Pausing recording.")
                    wasPausedByFocusLoss = true
                    pauseRecording()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPausedByFocusLoss && _recordingState.value.status == RecordingStatus.PAUSED) {
                    Log.d("RecordingService", "Audio focus regained. Resuming recording.")
                    wasPausedByFocusLoss = false
                    resumeRecording()
                }
            }
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var originalRingerMode: Int = -1

    private val stopMutex = Mutex()

    @Inject
    lateinit var draftDao: DraftDao

    @Inject
    lateinit var artifactRepository: ArtifactRepository

    @Inject
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var userSessionManager: UserSessionManager

    @Inject
    lateinit var storageManager: com.saurabh.artifact.util.StorageManager

    @Inject
    lateinit var localDraftManager: LocalDraftManager
    
    class RecordingBinder : Binder() {
        // No-op - preserved for standard service binding pattern
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder(applicationContext).apply {
            onError = { what, extra ->
                ArtifactLogger.e("RecordingService", "Hardware error: what=$what, extra=$extra")
                _recordingState.value = _recordingState.value.copy(
                    status = RecordingStatus.FAILED,
                    errorCode = "HARDWARE_ERROR_$what"
                )
                stopRecording()
            }
            onInfo = { what, extra ->
                Log.i("RecordingService", "Hardware info: what=$what, extra=$extra")
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || 
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        _recordingState.value = _recordingState.value.copy(errorCode = "STORAGE_FULL")
                    }
                    stopRecording()
                }
            }
            onStorageError = { e ->
                ArtifactLogger.e("RecordingService", "Storage error during recording", e)
                _recordingState.value = _recordingState.value.copy(errorCode = "STORAGE_FULL")
                stopRecording()
            }
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Artifact:RecordingWakeLock")
        
        createNotificationChannel()
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attr)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            return audioManager?.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun enableSilentMode() {
        try {
            audioManager?.let { am ->
                // Check for Do Not Disturb access
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (!notificationManager.isNotificationPolicyAccessGranted) {
                    Log.w("RecordingService", "Cannot enable silent mode: DND access not granted")
                    return
                }

                originalRingerMode = am.ringerMode
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
                Log.d("RecordingService", "Silent mode enabled (Original: $originalRingerMode)")
            }
        } catch (e: Exception) {
            ArtifactLogger.e("RecordingService", "Failed to enable silent mode", e)
        }
    }

    private fun restoreRingerMode() {
        try {
            audioManager?.let { am ->
                if (originalRingerMode != -1) {
                    am.ringerMode = originalRingerMode
                    Log.d("RecordingService", "Ringer mode restored to $originalRingerMode")
                    originalRingerMode = -1
                }
            }
        } catch (e: Exception) {
            ArtifactLogger.e("RecordingService", "Failed to restore ringer mode", e)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun handleAction(action: String?, draftId: String) {
        when (action) {
            ACTION_START -> {
                if (_recordingState.value.status == RecordingStatus.IDLE || 
                    _recordingState.value.status == RecordingStatus.FAILED ||
                    _recordingState.value.status == RecordingStatus.COMPLETED) {
                    
                    pacingJob?.cancel()
                    // Psychological Pacing: Intentional Delay before capture starts
                    pacingJob = serviceScope.launch {
                        Log.d("RecordingService", "Pacing: 1500ms intentional silence before capture")
                        _recordingState.value = RecordingState(status = RecordingStatus.PREPARING)
                        delay(1500.milliseconds)
                        startRecording(draftId)
                    }
                } else {
                    Log.w("RecordingService", "Ignoring ACTION_START: Already in state ${_recordingState.value.status}")
                }
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_CANCEL -> cancelRecording()
            else -> {
                if (action == null && _recordingState.value.status == RecordingStatus.IDLE) {
                    handleAction(ACTION_START, draftId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val draftId = intent?.getStringExtra("draft_id") ?: ""

        Log.d("RecordingService", "onStartCommand: action=$action")

        // 1. Ensure Foreground immediately
        try {
            val notification = createNotification(_recordingState.value.durationSeconds, _recordingState.value.status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            ArtifactLogger.e("RecordingService", "Foreground startup failed", e)
            _recordingState.value = RecordingState(status = RecordingStatus.FAILED)
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. Handle Actions
        handleAction(action, draftId)
        
        return START_NOT_STICKY
    }

    fun startRecording(draftId: String = "") {
        // Permission Check inside Service (Defense in Depth)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ArtifactLogger.e("RecordingService", "startRecording failed: Permission RECORD_AUDIO not granted")
            _recordingState.value = RecordingState(status = RecordingStatus.FAILED, errorCode = "PERMISSION_DENIED")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Storage Check
        if (!storageManager.isStorageAvailable()) {
            ArtifactLogger.e("RecordingService", "startRecording failed: Low storage (${storageManager.availableStorageMb} MB available)")
            _recordingState.value = RecordingState(status = RecordingStatus.FAILED, errorCode = "STORAGE_FULL")
            // Trigger emergency cleanup in background
            serviceScope.launch(Dispatchers.IO) {
                val allDrafts = draftDao.getAllDrafts()
                // We'll use LocalDraftManager's reconcileStorage here
                localDraftManager.reconcileStorage(allDrafts)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Acquire WakeLock to keep CPU alive during recording
        wakeLock?.acquire(10.minutes.inWholeMilliseconds)

        // Audio Focus Management: Ensure we have the microphone path cleared
        if (!requestAudioFocus()) {
            ArtifactLogger.e("RecordingService", "Could not acquire audio focus. Recording aborted.")
            _recordingState.value = RecordingState(status = RecordingStatus.FAILED, errorCode = "HARDWARE_IN_USE")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        enableSilentMode()

        // Set status to PREPARING immediately for UI responsiveness
        _recordingState.value = RecordingState(status = RecordingStatus.PREPARING)

        serviceScope.launch {
            val finalDraftId = draftId.ifEmpty { UUID.randomUUID().toString() }
            
            val draft = draftDao.getDraftById(finalDraftId)
            val file = draft?.let { File(it.localAudioPath) } ?: run {
                localDraftManager.createDraftFile(finalDraftId, "wav")
            }

            if (draft == null) {
                recordingRepository.createDraft(finalDraftId, file.absolutePath, 0, mimeType = "audio/wav")
            }

            try {
                // Initialize hardware on IO thread to prevent main-thread jank with a timeout
                val started = withTimeoutOrNull(5000.milliseconds) {
                    withContext(Dispatchers.IO) {
                        audioRecorder?.start(file, mode = RecordingMode.WAV_LOSSLESS, onDurableSync = { durableBytes ->
                            // OPTION A: Update DB checkpoint ONLY when durable sync occurs
                            val currentAmplitudes = _recordingState.value.amplitudes
                            val currentDuration = _recordingState.value.durationSeconds * 1000
                            
                            serviceScope.launch {
                                Log.d("RecordingService", "Durable Checkpoint: $durableBytes bytes persisted")
                                recordingRepository.updateRecordingProgress(
                                    id = finalDraftId,
                                    durationMs = currentDuration,
                                    amplitudes = currentAmplitudes,
                                    durableBytes = durableBytes
                                )
                            }
                        })
                    }
                    true
                } ?: false

                if (!started) {
                    ArtifactLogger.e("RecordingService", "Audio recorder startup timed out")
                    _recordingState.value = RecordingState(status = RecordingStatus.FAILED)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                
                // EMIT STATE ONLY AFTER SUCCESSFUL HARDWARE START
                _recordingState.value = RecordingState(
                    status = RecordingStatus.RECORDING, 
                    outputFile = file,
                    durationSeconds = 0,
                    amplitudes = emptyList(),
                    draftId = finalDraftId
                )
                
                draftDao.getDraftById(finalDraftId)?.let {
                    draftDao.update(it.copy(
                        status = it.status.copy(publication = SyncStatus.LocalOnly),
                        lifecycle = ArtifactLifecycle.RECORDING
                    ))
                }
                userSessionManager.setActiveDraftId(finalDraftId)

                startTimer()
                updateNotification(0, RecordingStatus.RECORDING)
                Log.d("RecordingService", "startRecording successful. State updated to RECORDING.")
            } catch (e: Exception) {
                ArtifactLogger.e("RecordingService", "startRecording failed: ${e.message}", e)
                _recordingState.value = _recordingState.value.copy(status = RecordingStatus.FAILED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun pauseRecording() {
        if (_recordingState.value.status != RecordingStatus.RECORDING) {
            Log.w("RecordingService", "pauseRecording() ignored: Status is not RECORDING")
            return
        }

        // Only clear focus-loss flag if we are manually pausing
        val focusLossState = wasPausedByFocusLoss
        
        audioRecorder?.pause()
        timerJob?.cancel()
        _recordingState.value = _recordingState.value.copy(status = RecordingStatus.PAUSED)
        
        wasPausedByFocusLoss = focusLossState
        
        _recordingState.value.draftId.let { id ->
            serviceScope.launch {
                draftDao.getDraftById(id)?.let {
                    draftDao.update(it.copy(
                        status = it.status.copy(publication = SyncStatus.LocalOnly)
                    ))
                }
            }
        }
        updateNotification(_recordingState.value.durationSeconds, RecordingStatus.PAUSED)
    }

    fun resumeRecording() {
        if (_recordingState.value.status != RecordingStatus.PAUSED) {
            Log.w("RecordingService", "resumeRecording() ignored: Status is not PAUSED")
            return
        }
        
        wasPausedByFocusLoss = false
        audioRecorder?.resume()
        startTimer()
        _recordingState.value = _recordingState.value.copy(status = RecordingStatus.RECORDING)
        
        _recordingState.value.draftId.let { id ->
            serviceScope.launch {
                draftDao.getDraftById(id)?.let {
                    draftDao.update(it.copy(
                        status = it.status.copy(publication = SyncStatus.LocalOnly),
                        lifecycle = ArtifactLifecycle.RECORDING
                    ))
                }
            }
        }
        updateNotification(_recordingState.value.durationSeconds, RecordingStatus.RECORDING)
    }

    fun stopRecording() {
        if ((_recordingState.value.status == RecordingStatus.IDLE) || 
            (_recordingState.value.status == RecordingStatus.COMPLETED)) {
            Log.d("RecordingService", "stopRecording() ignored: Already in state ${_recordingState.value.status}")
            return
        }

        wasPausedByFocusLoss = false
        serviceScope.launch {
            stopMutex.withLock {
                // Re-check status inside lock to prevent race conditions
                if (_recordingState.value.status == RecordingStatus.COMPLETED) {
                    Log.d("RecordingService", "stopRecording() race condition prevented: already COMPLETED")
                    return@withLock
                }

                try {
                    Log.d("RecordingService", "Finalizing session. State set to FINALIZING.")
                    _recordingState.value = _recordingState.value.copy(status = RecordingStatus.PREPARING) 

                    pacingJob?.cancel()
                    Log.d("RecordingService", "Stopping recording hardware...")
                    audioRecorder?.stop()
                    timerJob?.cancel()
                    abandonAudioFocus()
                    restoreRingerMode()
                    
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                    
                    // CRITICAL: Allow file buffers to flush and OS to sync file descriptors.
                    delay(500.milliseconds)
                    
                    val finalFile = _recordingState.value.outputFile
                    val draftId = _recordingState.value.draftId
                    
                    Log.d("RecordingService", "Validating output file: ${finalFile?.absolutePath}")
                    
                    // 1. HARD VALIDATION: Does the file exist and have data?
                    if (finalFile != null && finalFile.exists() && finalFile.length() > 0) {
                        val audioDataLength = finalFile.length() - WavHeaderUtils.HEADER_SIZE
                        val durationMs = WavHeaderUtils.calculateDurationMs(
                            audioDataLength = audioDataLength.coerceAtLeast(0),
                            sampleRate = 44100,
                            channels = 1,
                            bitsPerSample = 16
                        )

                        Log.d("RecordingService", "Calculated duration from file: $durationMs ms")

                        val result = recordingRepository.finalizeRecording(
                            id = draftId,
                            durationMs = durationMs,
                            durableBytes = audioDataLength.coerceAtLeast(0)
                        )

                        if (result.isSuccess) {
                            Log.d("RecordingService", "Atmospheric Handoff: Securing your reflection...")
                            
                            // hand off to the enhancement pipeline (which now starts with Transcoding)
                            publishingOrchestrator.startProcessing(draftId)

                            // CENTRALIZED CLEANUP: Clear the active session
                            userSessionManager.setActiveDraftId(null)
                            Log.d("RecordingService", "Session handed off to processing pipeline.")
                            
                            // Emit final session state to UI - ONLY AFTER SUCCESSFUL PERSISTENCE
                            _recordingState.value = _recordingState.value.copy(
                                status = RecordingStatus.COMPLETED
                            )
                        } else {
                            ArtifactLogger.e("RecordingService", "Finalization failed: ${result.exceptionOrNull()?.message}")
                            _recordingState.value = _recordingState.value.copy(status = RecordingStatus.FAILED)
                        }
                    } else {
                        ArtifactLogger.e("RecordingService", "Output file validation failed: file=${finalFile?.exists()}, length=${finalFile?.length()}")
                        _recordingState.value = _recordingState.value.copy(status = RecordingStatus.FAILED)
                        if (finalFile?.exists() == true) finalFile.delete()
                        draftDao.getDraftById(draftId)?.let {
                            draftDao.update(it.copy(
                                status = it.status.copy(processing = ProcessingStatus.Failed)
                            ))
                        }
                    }
                } catch (e: Exception) {
                    ArtifactLogger.e("RecordingService", "Error during stopRecording flow", e)
                    _recordingState.value = _recordingState.value.copy(status = RecordingStatus.FAILED)
                } finally {
                    Log.d("RecordingService", "Stopping service foreground and self")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    fun cancelRecording() {
        cleanup()
        
        val file = _recordingState.value.outputFile
        val draftId = _recordingState.value.draftId

        if (file?.exists() == true) {
            file.delete()
        }

        serviceScope.launch {
            draftDao.getDraftById(draftId)?.let { draftDao.delete(it) }
            userSessionManager.setActiveDraftId(null)
            
            // Ensure service stops after cleanup
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var seconds = _recordingState.value.durationSeconds
            var tick = 0
            
            // Reusable buffer for amplitudes to reduce allocation churn
            val internalAmplitudes = mutableListOf<Float>()
            
            while (isActive) {
                delay(50.milliseconds) // 20 ticks per second (50ms interval)
                tick++
                
                val rawAmplitude = audioRecorder?.maxAmplitude ?: 0
                _amplitude.value = rawAmplitude

                val normalizedAmplitude = (rawAmplitude.toFloat() / 32767f).coerceIn(0f, 1f)
                
                if (_recordingState.value.status == RecordingStatus.RECORDING) {
                    internalAmplitudes.add(normalizedAmplitude)

                    // Storage Monitoring
                    val availableMb = storageManager.availableStorageMb
                    
                    // Update storage low flag
                    if (_recordingState.value.isStorageLow != (availableMb < 200)) {
                        _recordingState.value = _recordingState.value.copy(isStorageLow = availableMb < 200)
                    }

                    // Emergency stop: Storage critically low (< 50MB)
                    if (tick % 40 == 0 && availableMb < 50) { 
                        ArtifactLogger.e("RecordingService", "Emergency stop: Storage critically low ($availableMb MB)")
                        _recordingState.value = _recordingState.value.copy(errorCode = "STORAGE_FULL")
                        withContext(Dispatchers.Main) {
                            stopRecording()
                        }
                        return@launch
                    }

                    // Throttle StateFlow updates for the full list to reduce UI recomposition pressure
                    // while maintaining 50ms real-time single amplitude updates.
                    val shouldUpdateFullState = tick % 4 == 0 // Every 200ms
                    
                    if (tick % 20 == 0) { // Every 1 second
                        seconds++
                        
                        _recordingState.value = _recordingState.value.copy(
                            durationSeconds = seconds,
                            amplitudes = internalAmplitudes.toList() // Snapshot of the list
                        )
                        updateNotification(seconds, RecordingStatus.RECORDING)
                    } else if (shouldUpdateFullState) {
                        _recordingState.value = _recordingState.value.copy(amplitudes = internalAmplitudes.toList())
                    }
                }
            }
        }
    }

    private fun createNotification(seconds: Long, status: RecordingStatus): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to_recording", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val pauseAction = if (status == RecordingStatus.RECORDING) {
            val pauseIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_PAUSE }
            val pausePending = PendingIntent.getService(this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", pausePending)
        } else {
            val resumeIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_RESUME }
            val resumePending = PendingIntent.getService(this, 3, resumeIntent, PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Resume", resumePending)
        }

        val returnAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_revert,
            "Return to Recording",
            pendingIntent
        )

        val timeText = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
        val statusText = when (status) {
            RecordingStatus.PREPARING -> "Creating a calm space..."
            RecordingStatus.PAUSED -> "Holding your reflection..."
            RecordingStatus.RECORDING -> {
                if (_recordingState.value.isStorageLow) "Listening... (Storage Low)"
                else "Listening to your essence..."
            }
            RecordingStatus.COMPLETED -> "Reflection secured."
            else -> "myArtifact"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(statusText)
            .setContentText("Duration: $timeText")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(pauseAction)
            .addAction(returnAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(seconds: Long, status: RecordingStatus) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Android 13+ requires POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("RecordingService", "POST_NOTIFICATIONS permission not granted. Skipping notification update.")
                return
            }
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(seconds, status))
        } catch (e: SecurityException) {
            ArtifactLogger.e("RecordingService", "SecurityException while updating notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d("RecordingService", "Service onDestroy() invoked.")
        
        // 1. Stop hardware and release resources synchronously first
        cleanup()
        
        // 2. Cancel the service scope
        serviceScope.cancel()
        
        super.onDestroy()
    }

    private fun cleanup() {
        wasPausedByFocusLoss = false
        pacingJob?.cancel()
        timerJob?.cancel()
        
        // Stop and release audio hardware
        audioRecorder?.release()
        
        abandonAudioFocus()
        restoreRingerMode()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Reset global state on destruction to signal completion or idle
        _recordingState.value = RecordingState(status = RecordingStatus.IDLE)
        _amplitude.value = 0
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "recording_channel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"

        private val _recordingState = MutableStateFlow(RecordingState())
        val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

        private val _amplitude = MutableStateFlow(0)
        val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

        data class RecordingState(
            val status: RecordingStatus = RecordingStatus.IDLE,
            val durationSeconds: Long = 0,
            val outputFile: File? = null,
            val amplitudes: List<Float> = emptyList(),
            val checksum: String? = null,
            val isEncrypted: Boolean = false,
            val draftId: String = "",
            val errorCode: String? = null,
            val isStorageLow: Boolean = false
        )
    }
}
