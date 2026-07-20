package com.saurabh.artifact.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.ArtifactDao
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.ArtifactEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.paging.ArtifactRemoteMediator
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactConversationMetadata
import com.saurabh.artifact.model.ArtifactDetail
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.model.ModerationMetadata
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.model.ReportReason
import com.saurabh.artifact.model.ReportStatus
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.UserReport
import com.saurabh.artifact.model.Visibility
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.service.ReflectionAIService
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.data.local.ArtifactEntityWithIndex
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import com.saurabh.artifact.util.NetworkUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("SameParameterValue")
@Singleton
class ArtifactRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val draftDao: dagger.Lazy<DraftDao>,
    private val userRepository: dagger.Lazy<UserRepository>,
    private val aiService: dagger.Lazy<ReflectionAIService>,
    private val personalizationEngine: dagger.Lazy<PersonalizationEngine>,
    private val settingsRepository: dagger.Lazy<SettingsRepository>,
    private val artifactDao: dagger.Lazy<ArtifactDao>,
    private val reportedArtifactDao: dagger.Lazy<com.saurabh.artifact.data.local.ReportedArtifactDao>,
    private val database: dagger.Lazy<AppDatabase>,
    private val pendingInteractionDao: dagger.Lazy<PendingInteractionDao>,
    private val diagnosticLogger: DiagnosticLogger
) {
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.Main + 
        CoroutineExceptionHandlerUtils.create("ArtifactRepository", "RepositoryScope failure")
    )

    suspend fun getArtifact(artifactId: String): Result<Artifact> = withContext(Dispatchers.IO) {
        try {
            val snapshot = firestore.collection("artifacts").document(artifactId).get().await()
            val artifact = snapshot.toObject(Artifact::class.java)?.copy(id = snapshot.id)
            if (artifact != null) {
                Result.success(artifact)
            } else {
                Result.failure(Exception("Artifact not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun runCacheCleanup(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Keep artifacts from the last 14 days
            val twoWeeksAgo = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000L)
            artifactDao.get().deleteOldArtifacts(twoWeeksAgo)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun getArtifactDetail(artifactId: String): Result<ArtifactDetail> = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("artifacts").document(artifactId).get().await()
            if (!doc.exists()) {
                return@withContext Result.failure(AppError.NotFound("ArtifactDetail", artifactId))
            }
            Result.success(mapDocumentToArtifactDetail(doc))
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    private suspend fun mapDocumentToArtifactDetail(doc: com.google.firebase.firestore.DocumentSnapshot): ArtifactDetail = withContext(Dispatchers.Default) {
        // Mandatory Fix: Downsample amplitudes to 64 points (from 128) to stay well within limits
        val rawAmplitudes = (doc["amplitudeData"] as? List<*>) ?: emptyList<Any>()
        val downsampledAmplitudes = downsampleAmplitudes(rawAmplitudes, 64)

        // Fetch Reaction Counts - Still IO
        // HARDENING: Treat reaction counts as optional metadata to allow offline access to details
        val reactionCounts = try {
            val reactionCountsDoc = firestore.collection("artifact_reaction_counts").document(doc.id).get().await()
            reactionCountsDoc.toObject(ArtifactReactionCounts::class.java)?.copy(artifactId = doc.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            
            diagnosticLogger.warn(
                category = DiagnosticCategory.FIRESTORE,
                eventName = "DETAIL_REACTION_COUNTS_CACHE_MISS",
                metadata = mapOf(
                    LogKeys.ARTIFACT_ID to doc.id,
                    LogKeys.EXCEPTION_CLASS to e.javaClass.simpleName,
                    LogKeys.ERROR_CODE to (e as? FirebaseFirestoreException)?.code?.name.orEmpty()
                )
            )
            // Return default counts to prevent failing the entire detail request
            ArtifactReactionCounts(artifactId = doc.id)
        }

        return@withContext ArtifactDetail(
            id = doc.id,
            amplitudeData = downsampledAmplitudes,
            reactionCounts = reactionCounts
        )
    }

    /**
     * Streams an artifact's metadata from Firestore for live updates (counts, status, etc.).
     */
    fun observeArtifact(artifactId: String): Flow<Artifact?> = callbackFlow {
        val docRef = firestore.collection("artifacts").document(artifactId)
        val subscription = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    diagnosticLogger.warn(DiagnosticCategory.FIRESTORE, "ARTIFACT_OBSERVE_DENIED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
                } else {
                    diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_OBSERVE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), error)
                }
                trySend(null)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val artifact = snapshot.toObject(Artifact::class.java)?.copy(id = snapshot.id)
                trySend(artifact)
            } else {
                trySend(null)
            }
        }
        awaitClose { subscription.remove() }
    }

    suspend fun getArtifactById(artifactId: String, forceRefresh: Boolean = false): Result<Artifact> = withContext(Dispatchers.IO) {
        try {
            // 1. Try local cache first if not forcing refresh
            if (!forceRefresh) {
                val local = artifactDao.get().getArtifactById(artifactId)
                if (local != null) {
                    // HARDENING: Implement 2-hour TTL for metadata freshness
                    val twoHoursMillis = 2 * 60 * 60 * 1000L
                    if (System.currentTimeMillis() - local.lastUpdated < twoHoursMillis) {
                        return@withContext Result.success(mapArtifactEntityToArtifact(local))
                    } else {
                        diagnosticLogger.debug(DiagnosticCategory.DATABASE, "ARTIFACT_CACHE_EXPIRED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
                    }
                }
            }

            // 2. Fallback to Firestore (or forced refresh)
            val doc = firestore.collection("artifacts").document(artifactId).get().await()
            if (doc.exists()) {
                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                if (artifact != null) {
                    // Update local cache
                    artifactDao.get().insertAll(listOf(mapArtifactToEntity(artifact)))
                    Result.success(artifact)
                } else {
                    val error = AppError.NotFound("Artifact", artifactId)
                    diagnosticLogger.error(
                        category = DiagnosticCategory.FIRESTORE,
                        eventName = "ARTIFACT_FETCH_FAILED",
                        metadata = mapOf(
                            LogKeys.ARTIFACT_ID to artifactId,
                            "source" to "Firestore",
                            "reason" to "Deserialization failed",
                            "exceptionClass" to "NullArtifact"
                        )
                    )
                    Result.failure(error)
                }
            } else {
                val error = AppError.NotFound("Artifact", artifactId)
                diagnosticLogger.error(
                    category = DiagnosticCategory.FIRESTORE,
                    eventName = "ARTIFACT_FETCH_FAILED",
                    metadata = mapOf(
                        LogKeys.ARTIFACT_ID to artifactId,
                        "source" to "Firestore",
                        "reason" to "Document does not exist",
                        "exceptionClass" to "NotFound"
                    )
                )
                Result.failure(error)
            }
        } catch (e: Exception) {
            val error = AppError.from(e)
            diagnosticLogger.error(
                category = DiagnosticCategory.FIRESTORE,
                eventName = "ARTIFACT_FETCH_FAILED",
                metadata = mutableMapOf(
                    LogKeys.ARTIFACT_ID to artifactId,
                    "source" to "Firestore",
                    "exceptionClass" to e.javaClass.simpleName,
                    "message" to (e.message ?: "No message"),
                    "stackTrace" to e.stackTraceToString(),
                    "causeClass" to (e.cause?.javaClass?.simpleName ?: "None"),
                    "causeMessage" to (e.cause?.message ?: "None")
                ).apply {
                    if (e is FirebaseFirestoreException) {
                        put("errorCode", e.code.name)
                    }
                }
            )
            Result.failure(error)
        }
    }

    /**
     * Optimized batch fetch for hydration (e.g., playback resumption).
     * Uses local cache first, then fetches missing items from Firestore in chunks.
     */
    suspend fun getArtifactsByIds(ids: List<String>): Result<List<Artifact>> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext Result.success(emptyList())
        
        try {
            // 1. Fetch all from local Room
            val localEntities = artifactDao.get().getArtifactsByIds(ids)
            val twoHoursMillis = 2 * 60 * 60 * 1000L
            val currentTime = System.currentTimeMillis()
            
            val validLocal = localEntities.filter { currentTime - it.lastUpdated < twoHoursMillis }
                .associateBy { it.id }
            
            val missingIds = ids.filter { !validLocal.containsKey(it) }
            
            if (missingIds.isEmpty()) {
                val ordered = ids.mapNotNull { id -> validLocal[id]?.let { mapArtifactEntityToArtifact(it) } }
                return@withContext Result.success(ordered)
            }

            // 2. Fetch missing from Firestore in chunks (whereIn limit is 10/30 depending on SDK)
            val fetchedRemote = mutableMapOf<String, Artifact>()
            missingIds.chunked(10).forEach { chunk ->
                val snapshot = firestore.collection("artifacts")
                    .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                    .get()
                    .await()
                
                snapshot.documents.forEach { doc ->
                    doc.toObject(Artifact::class.java)?.copy(id = doc.id)?.let { artifact ->
                        fetchedRemote[doc.id] = artifact
                    }
                }
            }

            // 3. Update local cache with new results
            if (fetchedRemote.isNotEmpty()) {
                artifactDao.get().insertAll(fetchedRemote.values.map { mapArtifactToEntity(it) })
            }

            // 4. Combine and maintain original ID order
            val results = ids.mapNotNull { id ->
                val artifact = validLocal[id]?.let { mapArtifactEntityToArtifact(it) } ?: fetchedRemote[id]
                artifact
            }
            
            Result.success(results)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "ARTIFACT_BATCH_FETCH_FAILED", 
                mapOf("count" to ids.size), 
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    suspend fun getPendingReports(): Result<List<UserReport>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("reports")
                .whereEqualTo("status", ReportStatus.PENDING.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
            
            val reports = snapshot.documents.mapNotNull { doc ->
                doc.toObject(UserReport::class.java)?.copy(id = doc.id)
            }
            Result.success(reports)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "FETCH_PENDING_REPORTS_FAILED", throwable = e)
            Result.failure(e)
        }
    }

    suspend fun resolveReport(
        reportId: String,
        artifactId: String,
        action: ModerationAction
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val reportRef = firestore.collection("reports").document(reportId)
        val artifactRef = firestore.collection("artifacts").document(artifactId)

        return@withContext try {
            firestore.runTransaction { transaction ->
                val status = when (action) {
                    ModerationAction.HIDE_ARTIFACT -> ReportStatus.RESOLVED
                    ModerationAction.DISMISS -> ReportStatus.DISMISSED
                }
                
                transaction.update(reportRef, "status", status.name)
                
                when (action) {
                    ModerationAction.HIDE_ARTIFACT -> {
                        transaction.update(artifactRef, "moderation.status", ModerationStatus.HIDDEN.name)
                        transaction.update(artifactRef, "isPublic", false)
                    }
                    ModerationAction.DISMISS -> { /* Just resolve the report */ }
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "REPORT_RESOLVE_FAILED", 
                mapOf(LogKeys.ARTIFACT_ID to artifactId, "reportId" to reportId), 
                e
            )
            Result.failure(e)
        }
    }

    enum class ModerationAction {
        HIDE_ARTIFACT,
        DISMISS
    }

    private fun downsampleAmplitudes(data: List<*>, target: Int): List<Float> {
        if (data.size <= target) return data.mapNotNull { (it as? Number)?.toFloat() }
        
        val step = data.size.toFloat() / target
        return (0 until target).map { i ->
            val index = (i * step).toInt().coerceIn(0, data.size - 1)
            (data[index] as? Number)?.toFloat() ?: 0f
        }
    }

    fun getUserArtifacts(userId: String, onlyActive: Boolean = false): Flow<List<Artifact>> = callbackFlow {
        val currentUserId = auth.currentUser?.uid
        val isPublicOnly = userId != currentUserId

        var query = firestore.collection("artifacts")
            .whereEqualTo("userId", userId)
            
        if (isPublicOnly) {
            query = query.whereEqualTo("isPublic", true)
        }

        if (onlyActive) {
            query = query.whereEqualTo("status", ArtifactStatus.ACTIVE.name)
        }
        
        query = query.orderBy("createdAt", Query.Direction.DESCENDING)

        val subscription = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                diagnosticLogger.error(
                    category = DiagnosticCategory.PROFILE,
                    eventName = "PROFILE_ARTIFACT_QUERY_FAILED",
                    metadata = mapOf(
                        "userId" to userId,
                        "errorCode" to (error as? FirebaseFirestoreException)?.code?.name.orEmpty(),
                        "errorMessage" to error.message.orEmpty(),
                        "isPublicOnly" to isPublicOnly
                    ),
                    throwable = error
                )
                trySend(emptyList())
                return@addSnapshotListener
            }
            
            repositoryScope.launch(Dispatchers.Default) {
                val artifacts = snapshot?.documents?.mapNotNull { doc ->
                    val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                    // If onlyActive is false, we still want to filter out truly broken ones (no status, etc.)
                    // but we allow PENDING_UPLOAD for the author.
                    if (artifact != null && (artifact.status == ArtifactStatus.ACTIVE || !onlyActive)) {
                        artifact
                    } else null
                } ?: emptyList()
                trySend(artifacts)
            }
        }
        awaitClose { subscription.remove() }
    }

    /**
     * Submits private feedback that is hidden from the public and the author.
     * Used for personalization and safety monitoring.
     */
    suspend fun submitPrivateFeedback(
        artifactId: String,
        userId: String,
        type: FeedbackType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val feedbackId = "${userId}_${artifactId}_${type.name}"
            val feedbackRef = firestore.collection("feedback_private").document(feedbackId)
            val artifactRef = firestore.collection("artifacts").document(artifactId)

            firestore.runTransaction { transaction ->
                val feedbackData = mapOf(
                    "userId" to userId,
                    "artifactId" to artifactId,
                    "type" to type.name,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                transaction[feedbackRef] = feedbackData

                // If it's a safety concern, increment the internal counter
                if (type == FeedbackType.SAFETY_CONCERN) {
                    transaction.update(artifactRef, "safetyConcernCount", FieldValue.increment(1))
                }
            }.await()

            // Trigger local re-ranking if it's "Not for me"
            if (type == FeedbackType.NOT_FOR_ME) {
                val hasConsent = settingsRepository.get().userSettings.first().dataCollectionConsent
                if (hasConsent) {
                    val artifact = firestore.collection("artifacts").document(artifactId).get().await()
                    val emotion = artifact.getString("emotion") ?: ""
                    if (emotion.isNotEmpty()) {
                        personalizationEngine.get().recordInteraction(emotion, weight = -1.0f)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "PRIVATE_FEEDBACK_FAILED", 
                mapOf(LogKeys.ARTIFACT_ID to artifactId, "type" to type.name), 
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Submits a user report for an artifact.
     * Uses device hash for privacy-preserving reporting.
     * Prevents duplicate reports from the same user.
     */
    suspend fun submitReport(
        artifactId: String,
        reason: ReportReason,
        optionalDescription: String,
        deviceIdHash: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val userId = auth.currentUser?.uid 
                ?: return@withContext Result.failure(AppError.Unauthenticated())

            // 1. Deterministic Report ID: {userId}_{artifactId}
            val reportId = "${userId}_${artifactId}"
            val reportData = mapOf(
                "artifactId" to artifactId,
                "reporterId" to userId,
                "reason" to reason.name,
                "optionalDescription" to optionalDescription,
                "deviceIdHash" to deviceIdHash,
                "createdAt" to FieldValue.serverTimestamp(),
                "status" to ReportStatus.PENDING.name
            )
            
            // 2. Submit the report document (Overwrite if exists)
            firestore.collection("reports").document(reportId).set(reportData).await()
            
            // 3. Update local Room DB for immediate hiding
            try {
                reportedArtifactDao.get().insert(
                    com.saurabh.artifact.data.local.ReportedArtifactEntity(
                        userId = userId,
                        artifactId = artifactId
                    )
                )
                // Force a delete from local cache as well to be sure
                artifactDao.get().deleteById(artifactId)
            } catch (e: Exception) {
                diagnosticLogger.error(
                    DiagnosticCategory.DATABASE, 
                    "REPORT_LOCAL_SYNC_FAILED", 
                    mapOf(LogKeys.ARTIFACT_ID to artifactId), 
                    e
                )
            }
                
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "REPORT_SUBMISSION_FAILED", 
                mapOf(LogKeys.ARTIFACT_ID to artifactId), 
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    fun getArtifactsPager(emotion: String?): Flow<PagingData<Pair<Artifact, Int>>> {
        val currentUserId = auth.currentUser?.uid ?: ""
        
        return Pager(
            config = PagingConfig(
                pageSize = 10,
                prefetchDistance = 2,
                initialLoadSize = 5,
                enablePlaceholders = false,
                maxSize = 30
            ),
            remoteMediator = ArtifactRemoteMediator(firestore, database.get(), currentUserId, emotion),
            pagingSourceFactory = { artifactDao.get().getArtifactsPaged(currentUserId) }
        ).flow.map { pagingData: PagingData<ArtifactEntityWithIndex> ->
            pagingData.map { wrapper -> 
                mapArtifactEntityToArtifact(wrapper.entity) to wrapper.absoluteIndex
            }
        }
    }

    private fun mapArtifactEntityToArtifact(entity: ArtifactEntity): Artifact {
        return Artifact(
            id = entity.id,
            userId = entity.userId,
            author = AuthorSnapshot(
                anonymousId = entity.authorAnonymousId,
                name = entity.authorName,
                sigil = entity.authorSigil,
                avatarSeed = entity.authorAvatarSeed,
                avatarColor = entity.authorAvatarColor,
                avatarConfig = try {
                    kotlinx.serialization.json.Json.decodeFromString(entity.authorAvatarConfigJson)
                } catch (_: Exception) {
                    AvatarConfig(seed = entity.authorAvatarSeed)
                }
            ),
            audioUrl = entity.audioUrl,
            createdAt = Timestamp(java.util.Date(entity.createdAt)),
            durationMs = entity.durationMs,
            title = entity.title,
            description = entity.description,
            emotion = entity.emotion.label,
            emotionTag = entity.emotionTag,
            playCount = entity.playCount,
            reactionCount = entity.reactionCount,
            reportCount = entity.reportCount,
            safetyConcernCount = entity.safetyConcernCount,
            reporterIds = entity.reporterIds,
            amplitudeData = entity.amplitudeData,
            transcriptUrl = entity.transcriptUrl,
            status = entity.status,
            isDraftField = entity.isDraft,
            conversationMetadata = ArtifactConversationMetadata(
                primaryStyle = entity.primaryStyle
            )
        )
    }

    private fun mapArtifactToEntity(artifact: Artifact): ArtifactEntity {
        return ArtifactEntity(
            id = artifact.id,
            userId = artifact.userId,
            authorAnonymousId = artifact.author.anonymousId,
            authorName = artifact.author.name,
            authorSigil = artifact.author.sigil,
            authorAvatarSeed = artifact.author.avatarSeed,
            authorAvatarColor = artifact.author.avatarColor,
            authorAvatarConfigJson = kotlinx.serialization.json.Json.encodeToString(artifact.author.avatarConfig),
            audioUrl = artifact.audioUrl,
            createdAt = artifact.createdAt.toDate().time,
            durationMs = artifact.durationMs,
            title = artifact.title,
            description = artifact.description,
            emotion = Emotion.entries.find { 
                it.label.equals(artifact.emotion, ignoreCase = true) ||
                it.name.equals(artifact.emotion, ignoreCase = true)
            } ?: Emotion.NEUTRAL,
            primaryStyle = artifact.conversationMetadata.primaryStyle,
            emotionTag = artifact.emotionTag,
            playCount = artifact.playCount,
            reactionCount = artifact.reactionCount,
            reportCount = artifact.reportCount,
            safetyConcernCount = artifact.safetyConcernCount,
            reporterIds = artifact.reporterIds,
            amplitudeData = artifact.amplitudeData,
            transcriptUrl = artifact.transcriptUrl,
            status = artifact.status,
            isDraft = artifact.isDraft,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Generates a contextually relevant reflection prompt using the AI service.
     */
    suspend fun getSmartReflectionPrompt(
        emotion: String?,
        context: String?,
        timeOfDay: String?
    ): ReflectionPrompt = withContext(Dispatchers.IO) {
        return@withContext aiService.get().generatePrompt(emotion, context, timeOfDay).getOrElse {
            // Fallback prompt if AI fails
            ReflectionPrompt(
                id = "fallback_${System.currentTimeMillis()}",
                category = PromptCategory.GENERAL,
                question = "What's one thing that stayed with you today?"
            )
        }
    }

    suspend fun recordPlay(userId: String?, emotion: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (emotion.isEmpty()) return@withContext Result.success(Unit)
        
        try {
            val hasConsent = settingsRepository.get().userSettings.first().dataCollectionConsent

            // 1. Persist locally for immediate personalization (AppSearch) if consent given
            if (hasConsent) {
                personalizationEngine.get().recordInteraction(emotion)
            }

            // 2. Persist to Firestore if authenticated AND consent given
            if (userId == null || !hasConsent) return@withContext Result.success(Unit)
            
            val userRef = firestore.collection("users").document(userId)
            firestore.runTransaction { transaction ->
                val userDoc = transaction[userRef]
                if (userDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val currentPrefs = userDoc["emotionPreferences"] as? Map<String, Long> ?: emptyMap()
                    val newCount = (currentPrefs[emotion] ?: 0L) + 1
                    val newPrefs = currentPrefs.toMutableMap().apply { put(emotion, newCount) }
                    transaction.update(userRef, "emotionPreferences", newPrefs)
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "PLAY_RECORD_FAILED", mapOf("emotion" to emotion), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Persists a private emotional bookmark for an artifact.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun saveArtifact(
        userId: String,
        artifact: Artifact,
        shelf: String = "Stayed With Me"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            diagnosticLogger.debug(DiagnosticCategory.RESONANCE, "ARTIFACT_SAVE_QUEUED", mapOf(LogKeys.ARTIFACT_ID to artifact.id))
            
            // 1. Record pending interaction
            val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                userId = userId,
                artifactId = artifact.id,
                interactionType = com.saurabh.artifact.data.local.InteractionType.SAVE,
                action = com.saurabh.artifact.data.local.InteractionAction.ADD,
                metadata = shelf
            )
            pendingInteractionDao.get().deleteByType(artifact.id, userId, com.saurabh.artifact.data.local.InteractionType.SAVE)
            pendingInteractionDao.get().insert(pending)

            // 2. Trigger Sync Worker
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "ARTIFACT_SAVE_QUEUE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifact.id), e)
            Result.failure(e)
        }
    }

    /**
     * Removes a private emotional bookmark.
     * PUBLIC API: Used by ViewModels. Enqueues interaction if unified queue is enabled.
     */
    suspend fun unsaveArtifact(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            diagnosticLogger.debug(DiagnosticCategory.RESONANCE, "ARTIFACT_UNSAVE_QUEUED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            
            // 1. Record pending interaction
            val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                userId = userId,
                artifactId = artifactId,
                interactionType = com.saurabh.artifact.data.local.InteractionType.SAVE,
                action = com.saurabh.artifact.data.local.InteractionAction.REMOVE
            )
            pendingInteractionDao.get().deleteByType(artifactId, userId, com.saurabh.artifact.data.local.InteractionType.SAVE)
            pendingInteractionDao.get().insert(pending)

            // 2. Trigger Sync Worker
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "ARTIFACT_UNSAVE_QUEUE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for artifact saving.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun saveArtifactToFirestore(userId: String, artifactId: String, shelf: String = "Stayed With Me"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = firestore.collection("users").document(userId)
                .collection("savedArtifacts").document(artifactId)

            docRef.set(mapOf(
                "savedAt" to Timestamp.now(),
                "shelf" to shelf
            )).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Internal synchronization method for artifact unsaving.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun unsaveArtifactFromFirestore(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.collection("users").document(userId)
                .collection("savedArtifacts").document(artifactId)
                .delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams the current user's saved artifact IDs for global UI synchronization.
     */
    fun getSavedArtifactIds(userId: String): Flow<Set<String>> = callbackFlow {
        val subscription = firestore.collection("users").document(userId)
            .collection("savedArtifacts")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptySet())
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.asSequence()?.map { it.id }?.toSet() ?: emptySet()
                trySend(ids)
            }
        awaitClose { subscription.remove() }
    }

    /**
     * Fetches all artifacts saved by the user, hydrated with full artifact data.
     */
    fun getSavedArtifacts(userId: String): Flow<List<Artifact>> = callbackFlow {
        val subscription = firestore.collection("users").document(userId)
            .collection("savedArtifacts")
            .orderBy("savedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val artifactIds = snapshot?.documents?.map { it.id } ?: emptyList()
                if (artifactIds.isEmpty()) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                repositoryScope.launch(Dispatchers.IO) {
                    val chunks = artifactIds.chunked(10)
                    val allSaved = mutableListOf<Artifact>()
                    for (chunk in chunks) {
                        val docs = firestore.collection("artifacts")
                            .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                            .get().await()
                        
                        val mappedChunk = withContext(Dispatchers.Default) {
                            docs.documents.mapNotNull { doc ->
                                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                                if (artifact == null || artifact.audioUrl.isEmpty() || artifact.status != ArtifactStatus.ACTIVE) return@mapNotNull null
                                
                                val reportCount = doc.getLong("reportCount") ?: 0L
                                val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()
                                
                                if (reportCount >= 3L || reporterIds.contains(userId)) {
                                    null
                                } else {
                                    artifact
                                }
                            }
                        }
                        allSaved.addAll(mappedChunk)
                    }
                    // Sort by the order of artifactIds (which is sorted by savedAt)
                    val sortedSaved = artifactIds.mapNotNull { id -> allSaved.find { it.id == id } }
                    trySend(sortedSaved)
                }
            }
        awaitClose { subscription.remove() }
    }

    suspend fun uploadArtifactResumable(
        userId: String,
        draft: ArtifactDraftEntity,
        onProgress: suspend (Long, Long, Uri?) -> Unit = { _, _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var currentRetry = 0

        val originalFile = File(draft.localAudioPath)
        if (!originalFile.exists()) return@withContext Result.failure(Exception("File missing: ${draft.localAudioPath}"))

        if (originalFile.length() == 0L) {
            return@withContext Result.failure(Exception("File is empty, aborting upload"))
        }

        try {
            val fileName = "artifacts/${userId}_${draft.id}.m4a"
            val fileRef = storage.reference.child(fileName)

            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("draftId", draft.id)
                .setCustomMetadata("checksum", draft.checksum ?: "")
                .setContentType("audio/x-m4a")
                .build()

            while (true) {
                diagnosticLogger.info(DiagnosticCategory.STORAGE, "UPLOAD_STARTED", mapOf(LogKeys.DRAFT_ID to draft.id))
                val loopResult: Result<String> = try {
                    withTimeout(5.minutes) {
                        val uploadTask = if (draft.uploadSessionUri != null) {
                            fileRef.putFile(originalFile.toUri(), metadata, draft.uploadSessionUri.toUri())
                        } else {
                            fileRef.putFile(originalFile.toUri(), metadata)
                        }

                        val taskSnapshot = try {
                            uploadTask.addOnProgressListener { snapshot ->
                                launch {
                                    onProgress(snapshot.bytesTransferred, snapshot.totalByteCount, snapshot.uploadSessionUri)
                                }
                            }.await()
                        } catch (e: com.google.firebase.storage.StorageException) {
                            // Detect expired or invalid resumable session (404/410)
                            val httpCode = e.httpResultCode
                            if (draft.uploadSessionUri != null && (httpCode == 404 || httpCode == 410)) {
                                diagnosticLogger.warn(DiagnosticCategory.STORAGE, "UPLOAD_SESSION_EXPIRED", mapOf(LogKeys.DRAFT_ID to draft.id, "httpCode" to httpCode))
                                // Clear the invalid session URI in the DB via DAO
                                draftDao.get().updateSyncProgress(draft.id, 0, draft.totalBytes, null)
                                
                                // Restart without the session URI
                                fileRef.putFile(Uri.fromFile(originalFile), metadata).addOnProgressListener { snapshot ->
                                    launch {
                                        onProgress(snapshot.bytesTransferred, snapshot.totalByteCount, snapshot.uploadSessionUri)
                                    }
                                }.await()
                            } else {
                                throw e
                            }
                        }

                        // HARDENING: Retrieve downloadUrl from snapshot storage reference for better reliability
                        val downloadUrl = retryDownloadUrlFetch(taskSnapshot.storage)
                            ?: return@withTimeout Result.failure(Exception("Upload succeeded but URL retrieval timed out. Check Firebase Storage rules and App Check status."))

                        Result.success(downloadUrl)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    
                    if (!isTransientError(e)) {
                        diagnosticLogger.error(DiagnosticCategory.STORAGE, "UPLOAD_FAILED_TERMINAL", mapOf(LogKeys.DRAFT_ID to draft.id), e)
                        Result.failure(e)
                    } else {
                        currentRetry++
                        if (currentRetry > maxRetries) {
                            diagnosticLogger.error(DiagnosticCategory.STORAGE, "UPLOAD_FAILED_MAX_RETRIES", mapOf(LogKeys.DRAFT_ID to draft.id), e)
                            Result.failure(e)
                        } else {
                            val delayTime = (2.0.pow(currentRetry.toDouble()).toLong() * 1000L)
                            diagnosticLogger.warn(DiagnosticCategory.STORAGE, "UPLOAD_RETRYING", mapOf(LogKeys.DRAFT_ID to draft.id, "retry" to currentRetry, "delayMs" to delayTime))
                            delay(delayTime.milliseconds)
                            continue // Loop again
                        }
                    }
                }
                return@withContext loopResult
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException("Unreachable"))
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.STORAGE, "UPLOAD_FAILED_WRAPPER", mapOf(LogKeys.DRAFT_ID to draft.id), e)
            Result.failure(e)
        }
    }

    /**
     * Determines if an error is transient (retriable) or terminal.
     */
    fun isTransientError(e: Throwable): Boolean {
        return NetworkUtils.isTransientError(e)
    }

    /**
     * Hardening: Specifically retries the download URL fetch to handle eventual consistency.
     */
    private suspend fun retryDownloadUrlFetch(ref: com.google.firebase.storage.StorageReference): String? {
        repeat(5) { attempt ->
            try {
                return ref.downloadUrl.await().toString()
            } catch (_: Exception) {
                diagnosticLogger.warn(DiagnosticCategory.STORAGE, "DOWNLOAD_URL_FETCH_RETRYING", mapOf("attempt" to attempt + 1))
                delay((attempt + 1).seconds)
            }
        }
        return null
    }

    suspend fun createArtifactDocument(
        userId: String,
        author: AuthorSnapshot,
        audioUrl: String,
        draft: ArtifactDraftEntity,
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        isPublic: Boolean = true,
        transcriptUrl: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // HARDENING: Audit Snapshot before persistence
            diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "ARTIFACT_DOCUMENT_PRE_REGISTER", mapOf(LogKeys.DRAFT_ID to draft.id))
            
            // 1. Recover Transcript from Frozen Snapshot
            val transcript = draft.frozenTranscriptJson?.toUnsecureString()?.let { json ->
                try {
                    kotlinx.serialization.json.Json.decodeFromString<List<TranscriptSegment>>(json)
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "TRANSCRIPT_DECODE_FAILED", mapOf(LogKeys.DRAFT_ID to draft.id), e)
                    emptyList()
                }
            } ?: emptyList()

            val artifact = Artifact(
                id = draft.id, // IDEMPOTENCY: Use draftId as the Firestore Document ID
                userId = userId,
                author = author,
                audioUrl = audioUrl,
                createdAt = Timestamp.now(),
                isPublic = isPublic,
                visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
                status = status,
                durationMs = draft.durationMs,
                title = draft.title ?: "Untitled Artifact",
                description = draft.description ?: "",
                emotion = draft.emotion?.label ?: "",
                emotionTag = draft.emotion?.label ?: "",
                prompt = "",
                transcript = transcript,
                transcriptUrl = transcriptUrl,
                amplitudeData = draft.amplitudeData,
                reactionVisibility = draft.reactionVisibility ?: ReactionVisibilityMode.APPROXIMATE,
                conversationMetadata = ArtifactConversationMetadata(
                    primaryStyle = draft.primaryStyle,
                    isAIGenerated = true
                ),
                moderation = ModerationMetadata(
                    status = ModerationStatus.SAFE,
                    updatedAt = Timestamp.now()
                )
            )
            val artifactData = mapArtifactToFirestoreData(artifact)
            
            // 2. Atomic Deterministic Write (Idempotent)
            firestore.runBatch { batch ->
                // A. Public Artifact Entry
                val artifactRef = firestore.collection("artifacts").document(draft.id)
                batch.set(artifactRef, artifactData)

                // B. Private Ownership Record
                val ownershipRef = firestore.collection("users").document(userId)
                    .collection("private").document("published_artifacts")
                    .collection("artifacts").document(draft.id)
                batch.set(ownershipRef, mapOf("createdAt" to Timestamp.now()))
            }.await()
            
            // Zero-Trust: Notification handled by backend (onArtifactCreated)

            Result.success(draft.id)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_DOCUMENT_CREATE_FAILED", mapOf(LogKeys.DRAFT_ID to draft.id), e)
            Result.failure(e)
        }
    }

    /**
     * Finalizes a pre-registered artifact by adding the audio URL and marking it as ACTIVE.
     */
    suspend fun finalizeArtifactDocument(
        artifactId: String,
        audioUrl: String,
        status: ArtifactStatus,
        isPublic: Boolean,
        transcriptUrl: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val updates = mutableMapOf<String, Any>(
                "audioUrl" to audioUrl,
                "status" to status.name,
                "isDraft" to (status == ArtifactStatus.DRAFT || status == ArtifactStatus.PENDING_UPLOAD),
                "isPublic" to isPublic,
                "visibility" to if (isPublic) Visibility.PUBLIC.name else Visibility.PRIVATE.name
            )
            transcriptUrl?.let { updates["transcriptUrl"] = it }

            firestore.collection("artifacts").document(artifactId)
                .update(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_DOCUMENT_FINALIZE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    suspend fun uploadTranscript(
        userId: String,
        draftId: String,
        transcriptJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "transcripts/${userId}_${draftId}.json"
            val fileRef = storage.reference.child(fileName)
            
            val metadata = StorageMetadata.Builder()
                .setContentType("application/json")
                .setCustomMetadata("draftId", draftId)
                .build()

            // --- RUNTIME INVESTIGATION INSTRUMENTATION START ---
            
            // Capture Auth State
            val user = auth.currentUser
            val authState = mapOf(
                "uid" to (user?.uid ?: "null"),
                "isAnonymous" to (user?.isAnonymous ?: false),
                "providerData" to (user?.providerData?.map { it.providerId } ?: emptyList<String>()),
                "creationTimestamp" to (user?.metadata?.creationTimestamp ?: 0L),
                "lastSignInTimestamp" to (user?.metadata?.lastSignInTimestamp ?: 0L),
                "isNull" to (user == null)
            )
            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "INVESTIGATION_AUTH_STATE", authState)

            // Token Refresh and Verification
            try {
                user?.let { u ->
                    val tokenResult = u.getIdToken(true).await()
                    diagnosticLogger.debug(DiagnosticCategory.STORAGE, "INVESTIGATION_TOKEN_REFRESH_SUCCESS", mapOf(
                        "expirationTimestamp" to tokenResult.expirationTimestamp,
                        "issuedAtTimestamp" to tokenResult.issuedAtTimestamp
                    ))
                } ?: diagnosticLogger.debug(DiagnosticCategory.STORAGE, "INVESTIGATION_TOKEN_REFRESH_SKIPPED", mapOf("reason" to "currentUser is null"))
            } catch (tokenEx: Exception) {
                diagnosticLogger.error(DiagnosticCategory.STORAGE, "INVESTIGATION_TOKEN_REFRESH_FAILED", emptyMap(), tokenEx)
            }

            // Storage Context
            val storageContext = mapOf(
                "bucket" to storage.reference.bucket,
                "path" to fileRef.path,
                "name" to fileRef.name,
                "parentPath" to (fileRef.parent?.path ?: "root")
            )
            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "INVESTIGATION_STORAGE_CONTEXT", storageContext)

            // Upload Metadata
            val uploadMetadata = mapOf(
                "contentType" to (metadata.contentType ?: "null"),
                "customMetadata" to metadata.customMetadataKeys.associateWith { metadata.getCustomMetadata(it) },
                "fileSize" to transcriptJson.toByteArray().size,
                "uploadPath" to fileName
            )
            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "INVESTIGATION_UPLOAD_METADATA", uploadMetadata)
            
            // --- RUNTIME INVESTIGATION INSTRUMENTATION END ---

            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "TRANSCRIPT_UPLOAD_STARTING", mapOf(LogKeys.DRAFT_ID to draftId, "path" to fileName))
            
            fileRef.putBytes(transcriptJson.toByteArray(), metadata).await()
            
            val downloadUrl = retryDownloadUrlFetch(fileRef)
                ?: return@withContext Result.failure(Exception("Transcript uploaded but download URL fetch failed."))
                
            Result.success(downloadUrl)
        } catch (e: Exception) {
            val extraParams = mutableMapOf<String, Any>(
                LogKeys.DRAFT_ID to draftId,
                "userId" to userId,
                "path" to "transcripts/${userId}_${draftId}.json",
                "exceptionType" to e.javaClass.name
            )

            if (e is com.google.firebase.storage.StorageException) {
                extraParams["errorCode"] = e.errorCode
                extraParams["httpCode"] = e.httpResultCode
                extraParams["message"] = e.message ?: "null"
                extraParams["localizedMessage"] = e.localizedMessage ?: "null"
            }

            // Capture complete exception chain
            val exceptionChain = mutableListOf<Map<String, String>>()
            var currentCause: Throwable? = e
            while (currentCause != null) {
                exceptionChain.add(mapOf(
                    "class" to currentCause.javaClass.name,
                    "message" to (currentCause.message ?: "null"),
                    "stackTrace" to currentCause.stackTraceToString()
                ))
                currentCause = currentCause.cause
            }
            extraParams["exceptionChain"] = exceptionChain
            
            diagnosticLogger.error(DiagnosticCategory.STORAGE, "TRANSCRIPT_UPLOAD_FAILED_INVESTIGATION", extraParams, e)
            Result.failure(e)
        }
    }

    suspend fun fetchTranscript(url: String): Result<List<TranscriptSegment>> = withContext(Dispatchers.IO) {
        try {
            val ref = storage.getReferenceFromUrl(url)
            val bytes = ref.getBytes(1024 * 1024).await() // 1MB limit for transcript JSON
            val json = String(bytes)
            val transcript = kotlinx.serialization.json.Json.decodeFromString<List<TranscriptSegment>>(json)
            Result.success(transcript)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.STORAGE, "TRANSCRIPT_FETCH_FAILED", mapOf("url" to url), e)
            Result.failure(e)
        }
    }

    /**
     * Renames a published artifact in Firestore.
     * Includes validation, history tracking, and local sync.
     */
    suspend fun renamePublishedArtifact(artifactId: String, newTitle: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmedTitle = newTitle.trim()
        if (trimmedTitle.isEmpty() || trimmedTitle.length > 70) {
            return@withContext Result.failure(IllegalArgumentException("Title must be between 1 and 70 characters"))
        }

        return@withContext try {
            val docRef = firestore.collection("artifacts").document(artifactId)
            
            firestore.runTransaction { transaction ->
                val snapshot = transaction[docRef]
                val currentTitle = snapshot.getString("title") ?: ""
                
                if (currentTitle != trimmedTitle) {
                    val history = snapshot["titleHistory"] as? List<*>
                    val newHistory = (history?.filterIsInstance<String>() ?: emptyList()) + currentTitle
                    
                    transaction.update(docRef, "title", trimmedTitle)
                    transaction.update(docRef, "titleHistory", newHistory.distinct().takeLast(5))
                }
            }.await()

            // Sync with local draft if it exists
            draftDao.get().getDraftByArtifactId(artifactId)?.let { draft ->
                draftDao.get().updateTitle(draft.id, trimmedTitle)
            }

            // Sync with local ArtifactEntity cache
            artifactDao.get().getArtifactById(artifactId)?.let { entity ->
                artifactDao.get().insertAll(listOf(entity.copy(title = trimmedTitle, lastUpdated = System.currentTimeMillis())))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_RENAME_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    /**
     * Determines if the current authenticated user has administrative privileges.
     * Administrative status is stored in a private settings document for security.
     */
    private suspend fun isCurrentUserAdmin(): Boolean {
        val userId = auth.currentUser?.uid ?: return false
        return try {
            val settingsDoc = firestore.collection("users").document(userId)
                .collection("private").document("settings")
                .get().await()
            settingsDoc.getBoolean("isAdmin") == true
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ADMIN_CHECK_FAILED", throwable = e)
            false
        }
    }

    /**
     * Marks a published artifact as DELETED in Firestore.
     * This is a "Soft Delete" that hides the artifact from all feeds and searches
     * but preserves the data for a potential "Recently Deleted" or "Undo" period.
     */
    suspend fun deletePublishedArtifact(artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUserId = auth.currentUser?.uid ?: return@withContext Result.failure(Exception("Unauthenticated"))
            
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val doc = artifactRef.get().await()
            
            if (!doc.exists()) {
                diagnosticLogger.warn(DiagnosticCategory.FIRESTORE, "ARTIFACT_DELETE_NOT_FOUND", mapOf(LogKeys.ARTIFACT_ID to artifactId))
                return@withContext Result.success(Unit)
            }
            
            val ownerId = doc.getString("userId")
            val isAdmin = isCurrentUserAdmin()
            
            if (ownerId != currentUserId && !isAdmin) {
                diagnosticLogger.warn(DiagnosticCategory.FIRESTORE, "ARTIFACT_DELETE_UNAUTHORIZED", mapOf(LogKeys.ARTIFACT_ID to artifactId, LogKeys.USER_ID to currentUserId))
                return@withContext Result.failure(Exception("Unauthorized: You do not own this reflection"))
            }
            
            // 1. Perform Soft Delete (Authority)
            firestore.runTransaction { transaction ->
                transaction.update(artifactRef, "status", ArtifactStatus.DELETED.name)
                transaction.update(artifactRef, "isPublic", false)
                transaction.update(artifactRef, "deletedAt", FieldValue.serverTimestamp())
            }.await()
            
            diagnosticLogger.info(DiagnosticCategory.FIRESTORE, "ARTIFACT_SOFT_DELETED", mapOf(LogKeys.ARTIFACT_ID to artifactId))

            // 2. Decrement artifactsCount
            userRepository.get().decrementArtifactsCount(currentUserId)

            // 3. Synchronize local Room database (Remove from local view)
            try {
                artifactDao.get().deleteById(artifactId)
                database.get().engagementDao().deleteEngagement(artifactId)
                // Also clear from Drafts if orphaned
                draftDao.get().getDraftByArtifactId(artifactId)?.let { draftDao.get().deleteById(it.id) }
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.DATABASE, "ARTIFACT_DELETE_LOCAL_SYNC_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_DELETE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    private fun mapArtifactToFirestoreData(artifact: Artifact): Map<String, Any?> {
        return mapOf(
            "userId" to artifact.userId,
            "author" to mapOf(
                "anonymousId" to artifact.author.anonymousId,
                "name" to artifact.author.name,
                "sigil" to artifact.author.sigil,
                "avatarSeed" to artifact.author.avatarSeed,
                "avatarColor" to artifact.author.avatarColor,
                "avatarConfig" to artifact.author.avatarConfig
            ),
            "audioUrl" to artifact.audioUrl,
            "createdAt" to artifact.createdAt,
            "isPublic" to artifact.isPublic,
            "visibility" to artifact.visibility.name,
            "status" to artifact.status.name,
            "isDraft" to (artifact.status == ArtifactStatus.DRAFT || artifact.status == ArtifactStatus.PENDING_UPLOAD),
            "durationMs" to artifact.durationMs,
            "title" to artifact.title,
            "description" to artifact.description,
            "emotion" to artifact.emotion,
            "emotionTag" to artifact.emotionTag,
            "emotionConfidence" to artifact.emotionConfidence,
            "prompt" to artifact.prompt,
            "reactionVisibility" to artifact.reactionVisibility.name,
            "amplitudeData" to artifact.amplitudeData,
            "moderation" to mapOf(
                "status" to artifact.moderation.status.name,
                "score" to artifact.moderation.score,
                "updatedAt" to artifact.moderation.updatedAt
            ),
            "playCount" to artifact.playCount,
            "reactionCount" to artifact.reactionCount,
            "reportCount" to artifact.reportCount,
            "transcriptUrl" to artifact.transcriptUrl,
            "conversationMetadata" to mapOf(
                "primaryStyle" to artifact.conversationMetadata.primaryStyle?.name,
                "isAIGenerated" to artifact.conversationMetadata.isAIGenerated
            )
        )
    }

    companion object {
        fun isTransientError(e: Throwable): Boolean {
            return NetworkUtils.isTransientError(e)
        }
    }

    /**
     * Optimistically updates the local Room database with new author identity information.
     * This ensures the Home Feed reflects changes immediately without waiting for a full reload.
     */
    suspend fun updateLocalAuthorSnapshot(userId: String, snapshot: AuthorSnapshot) = withContext(Dispatchers.IO) {
        try {
            artifactDao.get().updateAuthorInfo(
                userId = userId,
                name = snapshot.name,
                sigil = snapshot.sigil,
                seed = snapshot.avatarSeed,
                color = snapshot.avatarColor,
                configJson = kotlinx.serialization.json.Json.encodeToString(snapshot.avatarConfig)
            )
            diagnosticLogger.debug(DiagnosticCategory.DATABASE, "AUTHOR_SNAPSHOT_UPDATED", mapOf(LogKeys.USER_ID to userId))
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.DATABASE, "AUTHOR_SNAPSHOT_UPDATE_FAILED", mapOf(LogKeys.USER_ID to userId), e)
        }
    }
}
