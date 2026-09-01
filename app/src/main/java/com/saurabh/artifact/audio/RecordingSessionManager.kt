package com.saurabh.artifact.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.RecordingRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Unified Facade for the Recording Lifecycle.
 * This class is the Single Source of Truth for the UI and other components
 * to interact with and observe the recording process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecordingSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackCoordinator: PlaybackCoordinator,
    private val recordingRepository: RecordingRepository,
    private val userRepository: com.saurabh.artifact.repository.UserRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val localDraftManager: LocalDraftManager,
    private val draftDao: Lazy<DraftDao>,
    private val cleanupManager: ArtifactCleanupManager,
    private val diagnosticLogger: DiagnosticLogger
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionMutex = Mutex()

    // 1. Raw state from the Service
    private val _rawServiceState = RecordingService.recordingState
    val amplitude = RecordingService.amplitude

    // 2. Local metadata state
    private val _activeDraft = MutableStateFlow<ArtifactDraftEntity?>(null)

    private val _ritualSeconds = MutableStateFlow(0)
    private var ritualJob: Job? = null

    // 3. Unified Session State for the UI
    val sessionState: StateFlow<SessionState> = combine(
        _rawServiceState,
        _activeDraft,
        _ritualSeconds
    ) { serviceState, draft, ritualSecs ->
        SessionState(
            status = if (ritualSecs > 0) RecordingStatus.COUNTDOWN else serviceState.status,
            durationSeconds = serviceState.durationSeconds,
            amplitudes = serviceState.amplitudes,
            draftId = serviceState.draftId.ifEmpty { draft?.id ?: "" },
            outputFile = serviceState.outputFile,
            errorCode = serviceState.errorCode,
            ritualRemainingSeconds = ritualSecs,
            isStorageLow = serviceState.isStorageLow
        )
    }.stateIn(
        scope = managerScope,
        started = SharingStarted.Eagerly,
        initialValue = SessionState()
    )

    init {
        // Sync activeDraft metadata when service state changes (e.g. recovery after process death)
        managerScope.launch {
            _rawServiceState.collect { serviceState ->
                if (serviceState.draftId.isNotEmpty() && _activeDraft.value?.id != serviceState.draftId) {
                    val userId = userRepository.getCurrentUserId()
                    if (userId != null) {
                        val draft = draftDao.get().getDraftById(serviceState.draftId, userId)
                        _activeDraft.value = draft
                    }
                } else if (serviceState.status == RecordingStatus.IDLE) {
                    _activeDraft.value = null
                }
            }
        }

        // PERSONA RESET PROTECTION: Monitor identity version changes. 
        // If a version increase is detected (indicating a Same-UID persona swap)
        // while a recording is active, we authoritatively cancel the session.
        // This ensures the "Clean Break" invariant: Persona A's actions do not leak to Persona B.
        authRepository.currentUser
            .map { it?.uid }
            .distinctUntilChanged()
            .flatMapLatest { uid ->
                if (uid == null) flowOf(null)
                else userRepository.streamUserProfile(uid)
                    .map { it?.identityMetadata?.identityResetVersion }
                    .distinctUntilChanged()
            }
            .drop(1) // Ignore first emission
            .onEach { _ ->
                if (isRecordingActive()) {
                    diagnosticLogger.warn(DiagnosticCategory.RECORDING, "SESSION_CANCELLED_IDENTITY_RESET")
                    cancelSession()
                }
            }
            .launchIn(managerScope)
    }

    /**
     * Prepares for a new recording session by stopping any active playback.
     */
    fun prepareForRecording() {
        if (playbackCoordinator.isPlaying.value) {
            playbackCoordinator.stop()
        }
    }

    fun startRitual(seconds: Int = 10) {
        // If a ritual is already running, we might still want to update the time if it's vastly different
        // but for now, we just ensure the state is consistent.
        if (ritualJob?.isActive == true) {
            _ritualSeconds.value = seconds
            return
        }
        
        _ritualSeconds.value = seconds
        ritualJob = managerScope.launch {
            while (_ritualSeconds.value > 0) {
                delay(1.seconds)
                _ritualSeconds.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    fun skipRitual() {
        ritualJob?.cancel()
        _ritualSeconds.value = 0
    }

    fun cancelRitual() {
        ritualJob?.cancel()
        _ritualSeconds.value = 0
    }

    suspend fun startNewSession(explicitDraftId: String? = null) = sessionMutex.withLock {
        val currentStatus = _rawServiceState.value.status
        if (currentStatus != RecordingStatus.IDLE && currentStatus != RecordingStatus.FAILED && currentStatus != RecordingStatus.COMPLETED) {
            diagnosticLogger.warn(DiagnosticCategory.RECORDING, "SESSION_START_IGNORED", mapOf("currentStatus" to currentStatus.name))
            return@withLock
        }

        val userId = userRepository.getCurrentUserId() ?: run {
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "SESSION_START_FAILED_UNAUTHENTICATED")
            return@withLock
        }

        prepareForRecording()

        val draftId = explicitDraftId ?: UUID.randomUUID().toString()
        
        // Ensure draft exists in DB if we're starting fresh
        var draft = draftDao.get().getDraftById(draftId, userId)
        if (draft == null) {
            val file = withContext(Dispatchers.IO) {
                localDraftManager.createDraftFile(draftId, "wav")
            }
            recordingRepository.createDraft(draftId, file.absolutePath, 0).getOrThrow()
            draft = draftDao.get().getDraftById(draftId, userId)
            
            diagnosticLogger.info(
                DiagnosticCategory.DRAFT, 
                "DRAFT_SAVED", 
                mapOf(com.saurabh.artifact.diagnostics.LogKeys.DRAFT_ID to draftId)
            )
        }
        
        _activeDraft.value = draft
        
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("draft_id", draftId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pauseSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun cancelSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL
        }
        context.startService(intent)
        
        val draftId = _activeDraft.value?.id
        if (draftId != null) {
            managerScope.launch {
                cleanupManager.deleteDraft(draftId)
            }
        }
        _activeDraft.value = null
    }

    fun isRecordingActive(): Boolean {
        val status = _rawServiceState.value.status
        return status == RecordingStatus.RECORDING || 
               status == RecordingStatus.PAUSED ||
               status == RecordingStatus.PREPARING
    }

    data class SessionState(
        val status: RecordingStatus = RecordingStatus.IDLE,
        val durationSeconds: Long = 0,
        val amplitudes: List<Float> = emptyList(),
        val draftId: String = "",
        val outputFile: java.io.File? = null,
        val errorCode: String? = null,
        val ritualRemainingSeconds: Int = 0,
        val isStorageLow: Boolean = false
    )
}
