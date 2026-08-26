package com.saurabh.artifact.repository

import android.net.Uri
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
import com.saurabh.artifact.data.paging.ArtifactRemoteMediator
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactConversationMetadata
import com.saurabh.artifact.model.ArtifactDetail
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.EvidenceRevealResponse
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.SigilConfig
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
import com.saurabh.artifact.domain.prompt.ReflectionPromptManager
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
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val draftDao: dagger.Lazy<DraftDao>,
    private val userRepository: dagger.Lazy<UserRepository>,
    private val artifactDao: dagger.Lazy<ArtifactDao>,
    private val database: dagger.Lazy<com.saurabh.artifact.data.local.AppDatabase>,
    private val artifactLibraryRepository: dagger.Lazy<ArtifactLibraryRepository>,
    private val moderationRepository: dagger.Lazy<ArtifactModerationRepository>,
    private val publishingRepository: dagger.Lazy<ArtifactPublishingRepository>,
    private val artifactEngagementRepository: dagger.Lazy<ArtifactEngagementRepository>,
    private val reflectionPromptManager: dagger.Lazy<ReflectionPromptManager>,
    private val safetyPolicy: com.saurabh.artifact.domain.SafetyPolicy,
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
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    diagnosticLogger.warn(DiagnosticCategory.FIRESTORE, "ARTIFACT_OBSERVE_DENIED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
                } else {
                    diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_OBSERVE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), error)
                }
                close(error)
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

    suspend fun getPendingReports(): Result<List<UserReport>> = 
        moderationRepository.get().getPendingReports()

    suspend fun resolveReport(
        reportId: String,
        artifactId: String,
        action: ModerationAction
    ): Result<Unit> = moderationRepository.get().resolveReport(reportId, artifactId, action)

    suspend fun revealModerationEvidence(artifactId: String): Result<EvidenceRevealResponse> =
        moderationRepository.get().revealModerationEvidence(artifactId)

    enum class ModerationAction {
        HIDE_ARTIFACT,
        DISMISS,
        PLACE_ON_LEGAL_HOLD
    }

    private fun downsampleAmplitudes(data: List<*>, target: Int): List<Float> {
        if (data.size <= target) return data.mapNotNull { (it as? Number)?.toFloat() }
        
        val step = data.size.toFloat() / target
        return (0 until target).map { i ->
            val index = (i * step).toInt().coerceIn(0, data.size - 1)
            (data[index] as? Number)?.toFloat() ?: 0f
        }
    }

    fun getUserArtifacts(
        userId: String, 
        onlyActive: Boolean = false, 
        limit: Int = 20
    ): Flow<Pair<List<Artifact>, com.google.firebase.firestore.DocumentSnapshot?>> = callbackFlow {
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
            .limit(limit.toLong())

        val subscription = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                diagnosticLogger.error(
                    category = DiagnosticCategory.PROFILE,
                    eventName = "PROFILE_ARTIFACT_QUERY_FAILED",
                    metadata = mapOf(
                        "userId" to userId,
                        "errorCode" to error.code.name,
                        "errorMessage" to error.message.orEmpty(),
                        "isPublicOnly" to isPublicOnly
                    ),
                    throwable = error
                )
                trySend(emptyList<Artifact>() to null)
                return@addSnapshotListener
            }
            
            repositoryScope.launch(Dispatchers.Default) {
                val artifacts = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                        if (artifact == null) return@mapNotNull null

                        // If viewing someone else's profile, apply safety policy
                        if (isPublicOnly) {
                            val reportCount = doc.getLong("reportCount") ?: 0L
                            val safetyConcernCount = doc.getLong("safetyConcernCount") ?: 0L
                            val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()
                            
                            val artifactSnapshot = artifact.copy(
                                reportCount = reportCount,
                                safetyConcernCount = safetyConcernCount,
                                reporterIds = reporterIds.map { it.toString() }
                            )

                            val isEligible = safetyPolicy.isEligibleForDiscovery(
                                artifact = artifactSnapshot,
                                currentUserId = currentUserId,
                                isSuppressedByUser = reporterIds.contains(currentUserId)
                            )
                            
                            if (isEligible) artifactSnapshot else null
                        } else {
                            // Self-view: Show all non-deleted artifacts
                            if (artifact.status != ArtifactStatus.DELETED || !onlyActive) {
                                artifact
                            } else null
                        }
                    } catch (e: Exception) {
                        diagnosticLogger.error(
                            category = DiagnosticCategory.PROFILE,
                            eventName = "ARTIFACT_DESERIALIZATION_FAILED",
                            metadata = mapOf(
                                LogKeys.ARTIFACT_ID to doc.id,
                                "userId" to userId,
                                "context" to "getUserArtifacts"
                            ),
                            throwable = e
                        )
                        null
                    }
                } ?: emptyList()
                
                trySend(artifacts to snapshot?.documents?.lastOrNull())
            }
        }
        awaitClose { subscription.remove() }
    }

    /**
     * Fetches a single page of artifacts for a user.
     * Used for incremental loading (pagination) in the profile.
     */
    suspend fun getUserArtifactsPage(
        userId: String,
        limit: Int = 20,
        lastVisible: com.google.firebase.firestore.DocumentSnapshot? = null,
        onlyActive: Boolean = false
    ): Result<Pair<List<Artifact>, com.google.firebase.firestore.DocumentSnapshot?>> = withContext(Dispatchers.IO) {
        try {
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
                .limit(limit.toLong())

            if (lastVisible != null) {
                query = query.startAfter(lastVisible)
            }

            val snapshot = query.get().await()
            val artifacts = snapshot.documents.mapNotNull { doc ->
                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                if (artifact == null) return@mapNotNull null

                if (isPublicOnly) {
                    val reportCount = doc.getLong("reportCount") ?: 0L
                    val safetyConcernCount = doc.getLong("safetyConcernCount") ?: 0L
                    val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()
                    
                    val artifactSnapshot = artifact.copy(
                        reportCount = reportCount,
                        safetyConcernCount = safetyConcernCount,
                        reporterIds = reporterIds.map { it.toString() }
                    )

                    val isEligible = safetyPolicy.isEligibleForDiscovery(
                        artifact = artifactSnapshot,
                        currentUserId = currentUserId,
                        isSuppressedByUser = reporterIds.contains(currentUserId)
                    )
                    
                    if (isEligible) artifactSnapshot else null
                } else {
                    if (artifact.status != ArtifactStatus.DELETED || !onlyActive) {
                        artifact
                    } else null
                }
            }

            Result.success(artifacts to snapshot.documents.lastOrNull())
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE,
                "USER_ARTIFACTS_PAGE_FETCH_FAILED",
                mapOf("userId" to userId),
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
        // Bridge to ArtifactModerationRepository for business logic
        val result = moderationRepository.get().submitReport(artifactId, reason, optionalDescription, deviceIdHash)
        
        // Phase 2 Compliance: No longer performing direct local cache eviction here.
        // Hiding/Deletion of local cached artifact should be driven by status updates 
        // or delegated to ArtifactCleanupManager if a full purge is required.
        
        result
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
            remoteMediator = ArtifactRemoteMediator(
                firestore = firestore, 
                database = database.get(), 
                currentUserId = currentUserId, 
                emotion = emotion,
                safetyPolicy = safetyPolicy
            ),
            pagingSourceFactory = { 
                val relatedEmotionEnums = if (!emotion.isNullOrEmpty() && emotion != "All") {
                    com.saurabh.artifact.util.EmotionCategoryMapper.getRelatedEmotions(emotion).mapNotNull { label ->
                        Emotion.entries.find { it.label.equals(label, ignoreCase = true) }
                    }
                } else null
                artifactDao.get().getArtifactsPaged(currentUserId, relatedEmotionEnums) 
            }
        ).flow.map { pagingData: PagingData<ArtifactEntityWithIndex> ->
            pagingData.map { wrapper -> 
                mapArtifactEntityToArtifact(wrapper.entity) to wrapper.absoluteIndex
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun mapArtifactEntityToArtifact(entity: ArtifactEntity): Artifact {
        return Artifact(
            id = entity.id,
            userId = entity.userId,
            author = AuthorSnapshot(
                anonymousId = entity.authorAnonymousId,
                name = entity.authorName,
                sigil = entity.authorSigil,
                sigilSeed = entity.authorSigilSeed,
                sigilColor = entity.authorSigilColor,
                sigilConfig = try {
                    kotlinx.serialization.json.Json.decodeFromString(entity.authorSigilConfigJson)
                } catch (_: Exception) {
                    SigilConfig(seed = entity.authorSigilSeed)
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
            isEncrypted = entity.isEncrypted,
            conversationMetadata = ArtifactConversationMetadata(
                primaryStyle = entity.primaryStyle
            )
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun mapArtifactToEntity(artifact: Artifact): ArtifactEntity {
        return ArtifactEntity(
            id = artifact.id,
            userId = artifact.userId,
            authorAnonymousId = artifact.author.anonymousId,
            authorName = artifact.author.name,
            authorSigil = artifact.author.sigil,
            authorSigilSeed = artifact.author.sigilSeed,
            authorSigilColor = artifact.author.sigilColor,
            authorSigilConfigJson = kotlinx.serialization.json.Json.encodeToString(artifact.author.sigilConfig),
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
            isEncrypted = artifact.isEncrypted,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Generates a contextually relevant reflection prompt using the AI service.
     * Bridge to ReflectionPromptManager.
     */
    suspend fun getSmartReflectionPrompt(
        emotion: String?,
        context: String?,
        timeOfDay: String?
    ): ReflectionPrompt = reflectionPromptManager.get().getSmartReflectionPrompt(emotion, context, timeOfDay)

    /**
     * Records a playback event for an artifact.
     * Bridge to ArtifactEngagementRepository.
     */
    suspend fun recordPlay(userId: String?, artifactId: String, emotion: String): Result<Unit> = 
        artifactEngagementRepository.get().recordPlay(userId, artifactId, emotion)

    /**
     * Persists a private emotional bookmark for an artifact.
     * Bridge to ArtifactLibraryRepository.
     */
    suspend fun saveArtifact(
        userId: String,
        artifact: Artifact,
        shelf: String = "Stayed With Me"
    ): Result<Unit> = artifactLibraryRepository.get().saveArtifact(userId, artifact, shelf)

    /**
     * Removes a private emotional bookmark.
     * Bridge to ArtifactLibraryRepository.
     */
    suspend fun unsaveArtifact(userId: String, artifactId: String): Result<Unit> = 
        artifactLibraryRepository.get().unsaveArtifact(userId, artifactId)

    /**
     * Internal synchronization method for artifact saving.
     * Bridge to ArtifactLibraryRepository.
     */
    internal suspend fun saveArtifactToFirestore(userId: String, artifactId: String, shelf: String = "Stayed With Me"): Result<Unit> = 
        artifactLibraryRepository.get().syncSave(userId, artifactId, shelf)

    /**
     * Streams the current user's saved artifact IDs for global UI synchronization.
     * Bridge to ArtifactLibraryRepository.
     */
    fun getSavedArtifactIds(userId: String): Flow<Set<String>> = 
        artifactLibraryRepository.get().getSavedArtifactIds(userId)

    /**
     * Fetches all artifacts saved by the user, hydrated with full artifact data.
     * Bridge to ArtifactLibraryRepository.
     */
    fun getSavedArtifacts(userId: String): Flow<List<Artifact>> = 
        artifactLibraryRepository.get().getSavedArtifacts(userId)

    /**
     * Uploads an artifact audio file to Firebase Storage with resumable support.
     * Bridge: Delegated to ArtifactPublishingRepository.
     */
    suspend fun uploadArtifactResumable(
        userId: String,
        draft: ArtifactDraftEntity,
        onProgress: suspend (Long, Long, Uri?) -> Unit = { _, _, _ -> }
    ): Result<String> = publishingRepository.get().uploadArtifactResumable(userId, draft, onProgress)

    /**
     * Determines if an error is transient (retriable) or terminal.
     */
    fun isTransientError(e: Throwable): Boolean {
        return NetworkUtils.isTransientError(e)
    }

    suspend fun createArtifactDocument(
        userId: String,
        author: AuthorSnapshot,
        audioUrl: String,
        draft: ArtifactDraftEntity,
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        isPublic: Boolean = true,
        transcriptUrl: String? = null
    ): Result<String> = publishingRepository.get().createArtifactDocument(
        userId, author, audioUrl, draft, status, isPublic, transcriptUrl
    )

    /**
     * Finalizes a pre-registered artifact by adding the audio URL and marking it as ACTIVE.
     * Bridge: Delegated to ArtifactPublishingRepository.
     */
    suspend fun finalizeArtifactDocument(
        artifactId: String,
        audioUrl: String,
        status: ArtifactStatus,
        isPublic: Boolean,
        transcriptUrl: String? = null
    ): Result<Unit> = publishingRepository.get().finalizeArtifactDocument(
        artifactId, audioUrl, status, isPublic, transcriptUrl
    )

    /**
     * Hardening: Specifically retries the download URL fetch to handle eventual consistency.
     * Legacy: Kept for uploadTranscript support.
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

    /**
     * LEGACY COMPATIBILITY: Uploads a transcript to Firebase Storage.
     * Remove after legacy transcript support is retired.
     */
    suspend fun uploadTranscript(
        userId: String,
        draftId: String,
        transcriptJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "transcripts/${userId}_$draftId.json"
            val fileRef = storage.reference.child(fileName)
            
            val metadata = StorageMetadata.Builder()
                .setContentType("application/json")
                .setCustomMetadata("draftId", draftId)
                .build()

            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "TRANSCRIPT_UPLOAD_STARTING", mapOf(LogKeys.DRAFT_ID to draftId, "path" to fileName))
            
            fileRef.putBytes(transcriptJson.toByteArray(), metadata).await()
            
            val downloadUrl = retryDownloadUrlFetch(fileRef)
                ?: return@withContext Result.failure(Exception("Transcript uploaded but download URL fetch failed."))
                
            Result.success(downloadUrl)
        } catch (e: Exception) {
            val extraParams = mutableMapOf<String, Any>(
                LogKeys.DRAFT_ID to draftId,
                "userId" to userId,
                "path" to "transcripts/${userId}_$draftId.json",
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
            
            diagnosticLogger.error(DiagnosticCategory.STORAGE, "TRANSCRIPT_UPLOAD_FAILED", extraParams, e)
            Result.failure(e)
        }
    }

    /**
     * LEGACY COMPATIBILITY: Fetches a transcript from Firebase Storage.
     * Remove after legacy transcript support is retired.
     */
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
            draftDao.get().internalGetDraftByArtifactIdAgnostic(artifactId)?.let { draft ->
                draftDao.get().updateTitle(draft.id, draft.userId, trimmedTitle)
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
     * Bridge to ArtifactModerationRepository.
     */
    private suspend fun isCurrentUserAdmin(): Boolean = moderationRepository.get().isCurrentUserAdmin()

    /**
     * Authoritatively performs remote deletion of a published artifact in Firestore.
     * This method focuses ONLY on the remote state transition to DELETED.
     * Local cleanup is handled by the ArtifactCleanupManager pipeline.
     */
    suspend fun performRemoteDelete(artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
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
            
            // Perform Soft Delete (Authority) - Bridge to ArtifactModerationRepository
            val remoteResult = moderationRepository.get().softDeleteArtifact(artifactId)
            if (remoteResult.isFailure) return@withContext remoteResult
            
            diagnosticLogger.info(DiagnosticCategory.FIRESTORE, "ARTIFACT_SOFT_DELETED", mapOf(LogKeys.ARTIFACT_ID to artifactId))

            // Decrement artifactsCount asynchronously
            userRepository.get().enqueueArtifactCountDecrement(currentUserId, artifactId)

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_DELETE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
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
                seed = snapshot.sigilSeed,
                color = snapshot.sigilColor,
                configJson = kotlinx.serialization.json.Json.encodeToString(snapshot.sigilConfig)
            )
            diagnosticLogger.debug(DiagnosticCategory.DATABASE, "AUTHOR_SNAPSHOT_UPDATED", mapOf(LogKeys.USER_ID to userId))
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.DATABASE, "AUTHOR_SNAPSHOT_UPDATE_FAILED", mapOf(LogKeys.USER_ID to userId), e)
        }
    }
}
