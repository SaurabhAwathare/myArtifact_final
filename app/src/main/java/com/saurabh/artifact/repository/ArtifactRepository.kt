package com.saurabh.artifact.repository

import android.net.Uri
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.paging.ArtifactPagingSource
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.security.SecurityArchitecture
import com.saurabh.artifact.service.ReflectionAIService
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SafetyLevel
import com.saurabh.artifact.service.SafetyResult
import com.saurabh.artifact.data.local.toEntity
import com.saurabh.artifact.data.local.toDomainModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.saurabh.artifact.data.local.ArtifactEntity
import androidx.paging.map
import com.saurabh.artifact.data.paging.ArtifactRemoteMediator
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Suppress("SameParameterValue")
@Singleton
class ArtifactRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val auth: com.google.firebase.auth.FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val draftDao: DraftDao,
    private val userRepository: UserRepository,
    private val userProfileManager: dagger.Lazy<UserProfileManager>,
    private val localDraftManager: com.saurabh.artifact.audio.LocalDraftManager,
    private val aiService: dagger.Lazy<ReflectionAIService>,
    private val safetyEvaluator: dagger.Lazy<SafetyEvaluator>,
    private val personalizationEngine: dagger.Lazy<com.saurabh.artifact.service.PersonalizationEngine>,
    private val notificationRepository: NotificationRepository,
    private val artifactDao: com.saurabh.artifact.data.local.ArtifactDao,
    private val database: com.saurabh.artifact.data.local.AppDatabase,
    private val promptDao: com.saurabh.artifact.data.local.PromptDao
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    suspend fun deleteDraftLocally(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        SecurityArchitecture.secureDelete(File(draft.localAudioPath))
        draftDao.delete(draft)
    }

    suspend fun saveDraftPrivately(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.updateDraftState(draftId, ArtifactDraftState.SAVED_LOCALLY)
    }

    suspend fun updateEmotionalConfirmation(draftId: String, isReady: Boolean, confidence: Float) = withContext(Dispatchers.IO) {
        draftDao.updateEmotionalConfirmation(draftId, isReady, confidence)
        if (isReady) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.READY_TO_PUBLISH)
        }
    }

    suspend fun setCooldown(draftId: String, durationMinutes: Int) = withContext(Dispatchers.IO) {
        val expiry = System.currentTimeMillis() + durationMinutes * 60 * 1000
        draftDao.updateCooldown(draftId, expiry)
        draftDao.updateDraftState(draftId, ArtifactDraftState.WAITING_COOLDOWN)
    }

    /**
     * Fetches a smart, context-aware reflection prompt with real-time safety evaluation.
     * Prioritizes safety overrides for high-risk emotional signals.
     * Implements local caching and offline fallbacks.
     */
    suspend fun getSmartReflectionPrompt(
        emotion: String?,
        context: String? = null,
        timeOfDay: String? = null
    ): ReflectionPrompt {
        // 1. Pre-emptive Safety Assessment (Internal logic - no data leaves device if HIGH)
        val safetyResult = withContext(Dispatchers.Default) {
            safetyEvaluator.get().evaluate(context)
        }
        if ((safetyResult.level == SafetyLevel.HIGH) && (safetyResult.suggestedPrompt != null)) {
            return safetyResult.suggestedPrompt
        }

        return try {
            val aiResponse = aiService.get().generatePrompt(emotion, context, timeOfDay).getOrNull()
            
            if (aiResponse != null) {
                // 2. Post-generation Safety Filter (Normalization)
                val safeText = withContext(Dispatchers.Default) {
                    safetyEvaluator.get().filterAIOutput(aiResponse.question)
                }
                val finalPrompt = aiResponse.copy(question = safeText)
                
                // 3. Cache the successful prompt locally
                repositoryScope.launch(Dispatchers.IO) {
                    promptDao.insertPrompts(listOf(finalPrompt.toEntity()))
                }
                
                finalPrompt
            } else {
                fetchFallbackPrompt(emotion, safetyResult)
            }
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "AI prompt generation failed, falling back", e)
            fetchFallbackPrompt(emotion, safetyResult)
        }
    }

    private suspend fun fetchFallbackPrompt(emotion: String?, safetyResult: SafetyResult): ReflectionPrompt {
        // A. Priority Fallback: Safety suggestions
        if ((safetyResult.level == SafetyLevel.MEDIUM) && (safetyResult.suggestedPrompt != null)) {
            return safetyResult.suggestedPrompt
        }

        // B. Local Cache Fallback: Fetch from Room
        return withContext(Dispatchers.IO) {
            val localPrompts = if (emotion != null) {
                // Simplified: search by tone or mood if possible, otherwise all
                // For now, we'll just get a random one from recent usage
                promptDao.getRecentPrompts(limit = 10)
            } else {
                promptDao.getRecentPrompts(limit = 10)
            }

            if (localPrompts.isNotEmpty()) {
                localPrompts.random().toDomainModel()
            } else {
                // C. Hardcoded Fallback
                ReflectionPromptProvider.getRandomPrompt()
            }
        }
    }

    private fun mapArtifactToFirestore(artifact: Artifact): Map<String, Any?> {
        return mapOf(
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
            "isDraft" to artifact.isDraft,
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
            "commentCount" to artifact.commentCount,
            "reportCount" to artifact.reportCount,
            "transcriptUrl" to artifact.transcriptUrl
        )
    }

    suspend fun uploadArtifact(
        userId: String,
        username: String,
        audioFileUri: Uri,
        title: String,
        isPublic: Boolean,
        duration: Long,
        emotion: String = "",
        emotionTag: String = "",
        emotionConfidence: Float = 0f,
        prompt: String = "",
        redactionFilter: String = "",
        avatarSeed: String = "",
        amplitudeData: List<Float> = emptyList(),
        reactionVisibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val fileName = "artifacts/${userId}_${System.currentTimeMillis()}.m4a"
            val fileRef = storage.reference.child(fileName)

            // Ensure URI has a scheme for Firebase Storage
            val uploadUri = if (audioFileUri.scheme == null) {
                Uri.fromFile(File(audioFileUri.path ?: ""))
            } else {
                audioFileUri
            }

            // HARDENING: Check file existence before upload attempt
            if (uploadUri.scheme == "file") {
                val file = File(uploadUri.path ?: "")
                if (!file.exists() || file.length() == 0L) {
                    Log.e("ArtifactRepository", "Upload failed: File missing or empty at ${file.absolutePath}")
                    return@withContext Result.failure(Exception("File missing or empty"))
                }
            }

            val userProfile = userRepository.getOrCreateProfile()
            val artifactId = "art_${userId}_${System.currentTimeMillis()}"

            // 1. Pre-register Document
            val preDraft = ArtifactDraftEntity(
                id = artifactId,
                localAudioPath = uploadUri.path ?: "",
                title = title,
                durationMs = duration,
                isPublic = isPublic,
                emotion = emotion,
                amplitudeData = amplitudeData,
                reactionVisibility = reactionVisibility
            )
            
            // 1.5 Fetch transcript if available locally (Optional optimization)
            // Note: In this direct upload method, we might not have a frozen transcript yet
            // if called from somewhere other than the standard publish flow.
            
            createArtifactDocument(
                userId = userId,
                username = username,
                audioUrl = "",
                draft = preDraft,
                avatarSeed = avatarSeed.ifEmpty { userProfile.avatarSeed },
                avatarColor = userProfile.avatarColor,
                avatarConfig = userProfile.avatarConfig,
                anonymousId = userProfile.anonymousId,
                anonymousSigil = userProfile.anonymousSigil,
                status = ArtifactStatus.PENDING_UPLOAD,
                isPublic = false
            ).getOrThrow()

            // 2. Upload File
            fileRef.putFile(uploadUri)
                .addOnProgressListener { taskSnapshot ->
                    val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount)
                    onProgress(progress.toFloat())
                }.await()

            val downloadUrl = fileRef.downloadUrl.await().toString()

            // 3. Finalize Document
            finalizeArtifactDocument(
                artifactId = artifactId,
                audioUrl = downloadUrl,
                status = ArtifactStatus.ACTIVE,
                isPublic = isPublic
            ).getOrThrow()

            // 4. Create in-app notification for the user
            notificationRepository.createNotification(
                userId = userId,
                message = "You released a new reflection into the world: $title ✨",
                artifactId = artifactId,
                type = NotificationType.SYSTEM
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Upload failed", e)
            Result.failure(e)
        }
    }

    /**
     * Cleans up old cached artifacts to prevent local DB growth.
     */
    suspend fun runCacheCleanup() = withContext(Dispatchers.IO) {
        // Keep artifacts from the last 14 days
        val twoWeeksAgo = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000L)
        artifactDao.deleteOldArtifacts(twoWeeksAgo)
    }

    suspend fun getArtifactDetail(artifactId: String): ArtifactDetail = withContext(Dispatchers.IO) {
        val doc = firestore.collection("artifacts").document(artifactId).get().await()
        return@withContext mapDocumentToArtifactDetail(doc)
    }

    private suspend fun mapDocumentToArtifactDetail(doc: com.google.firebase.firestore.DocumentSnapshot): ArtifactDetail = withContext(Dispatchers.Default) {
        // Mandatory Fix: Downsample amplitudes to 64 points (from 128) to stay well within limits
        val rawAmplitudes = doc.get("amplitudeData") as? List<*> ?: emptyList<Any>()
        val downsampledAmplitudes = downsample(rawAmplitudes, 64)

        // Fetch Reaction Counts - Still IO
        val reactionCountsDoc = firestore.collection("artifact_reaction_counts").document(doc.id).get().await()
        val reactionCounts = reactionCountsDoc.toObject(ArtifactReactionCounts::class.java)?.copy(artifactId = doc.id)

        return@withContext ArtifactDetail(
            id = doc.id,
            amplitudeData = downsampledAmplitudes,
            reactionCounts = reactionCounts,
            comments = (doc.get("comments") as? List<Map<String, Any>>)?.map { map ->
                ArtifactComment(
                    id = map["id"] as? String ?: "",
                    authorId = map["authorId"] as? String ?: "",
                    authorAnonymousName = map["authorName"] as? String ?: "",
                    authorAvatarSeed = map["authorAvatarSeed"] as? String ?: "",
                    createdAt = map["createdAt"] as? Timestamp ?: Timestamp.now()
                )
            } ?: emptyList()
        )
    }

    suspend fun getArtifactById(artifactId: String, forceRefresh: Boolean = false): Artifact? = withContext(Dispatchers.IO) {
        // 1. Try local cache first if not forcing refresh
        if (!forceRefresh) {
            val local = artifactDao.getArtifactById(artifactId)
            if (local != null) {
                // HARDENING: Implement 2-hour TTL for metadata freshness
                val twoHoursMillis = 2 * 60 * 60 * 1000L
                if (System.currentTimeMillis() - local.lastUpdated < twoHoursMillis) {
                    return@withContext mapEntityToArtifact(local)
                } else {
                    Log.d("ArtifactRepository", "Cache expired for $artifactId, refreshing from Firestore")
                }
            }
        }

        // 2. Fallback to Firestore (or forced refresh)
        return@withContext try {
            val doc = firestore.collection("artifacts").document(artifactId).get().await()
            if (doc.exists()) {
                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                if (artifact != null) {
                    // Update local cache
                    artifactDao.insertAll(listOf(mapToEntity(artifact)))
                    artifact
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Refreshes local metadata for an artifact from Firestore.
     */
    suspend fun refreshArtifactMetadata(artifactId: String) {
        getArtifactById(artifactId, forceRefresh = true)
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
            Log.e("ArtifactRepository", "Error fetching reports", e)
            Result.failure(e)
        }
    }

    suspend fun resolveReport(
        reportId: String,
        artifactId: String,
        action: ModerationAction,
        commentId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val reportRef = firestore.collection("reports").document(reportId)
        val artifactRef = firestore.collection("artifacts").document(artifactId)
        val commentRef = commentId?.let { firestore.collection("comments").document(it) }

        return@withContext try {
            firestore.runTransaction { transaction ->
                val status = when (action) {
                    ModerationAction.HIDE_ARTIFACT, ModerationAction.BLOCK_COMMENT, ModerationAction.APPROVE_COMMENT -> ReportStatus.RESOLVED
                    ModerationAction.DISMISS -> ReportStatus.DISMISSED
                }
                
                transaction.update(reportRef, "status", status.name)
                
                when (action) {
                    ModerationAction.HIDE_ARTIFACT -> {
                        transaction.update(artifactRef, "moderation.status", ModerationStatus.HIDDEN.name)
                        transaction.update(artifactRef, "isPublic", false)
                    }
                    ModerationAction.BLOCK_COMMENT -> {
                        commentRef?.let { transaction.update(it, "moderationState", CommentModerationState.BLOCKED.name) }
                    }
                    ModerationAction.APPROVE_COMMENT -> {
                        commentRef?.let { transaction.update(it, "moderationState", CommentModerationState.APPROVED.name) }
                    }
                    ModerationAction.DISMISS -> { /* Just resolve the report */ }
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Error resolving report", e)
            Result.failure(e)
        }
    }

    enum class ModerationAction {
        HIDE_ARTIFACT,
        BLOCK_COMMENT,
        APPROVE_COMMENT,
        DISMISS
    }

    private fun downsample(data: List<*>, target: Int): List<Float> {
        if (data.size <= target) return data.mapNotNull { (it as? Number)?.toFloat() }
        
        val step = data.size.toFloat() / target
        return (0 until target).map { i ->
            val index = (i * step).toInt().coerceIn(0, data.size - 1)
            (data[index] as? Number)?.toFloat() ?: 0f
        }
    }

    fun getUserArtifacts(userId: String): Flow<List<Artifact>> = callbackFlow {
        val query = firestore.collection("artifacts")
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)

        val subscription = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            
            repositoryScope.launch(Dispatchers.Default) {
                val artifacts = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Artifact::class.java)?.copy(id = doc.id)
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
                transaction.set(feedbackRef, feedbackData)

                // If it's a safety concern, increment the internal counter
                if (type == FeedbackType.SAFETY_CONCERN) {
                    transaction.update(artifactRef, "safetyConcernCount", FieldValue.increment(1))
                }
            }.await()

            // Trigger local re-ranking if it's "Not for me"
            if (type == FeedbackType.NOT_FOR_ME) {
                val artifact = firestore.collection("artifacts").document(artifactId).get().await()
                val emotion = artifact.getString("emotion") ?: ""
                if (emotion.isNotEmpty()) {
                    personalizationEngine.get().recordInteraction(emotion, weight = -1.0f)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Private feedback failed", e)
            Result.failure(e)
        }
    }

    /**
     * Submits a user report for an artifact.
     * Uses device hash for privacy-preserving reporting.
     */
    suspend fun submitReport(
        artifactId: String,
        reason: ReportReason,
        details: String,
        deviceId: Int,
        commentId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            // 1. Ensure we have an authenticated user
            val userId = auth.currentUser?.uid 
                ?: return@withContext Result.failure(AppError.Unauthenticated())

            val reportId = UUID.randomUUID().toString()
            val reportData = mutableMapOf<String, Any?>(
                "artifactId" to artifactId,
                "commentId" to commentId,
                "reporterDeviceId" to deviceId,
                "reason" to reason.name,
                "details" to details,
                "createdAt" to Timestamp.now(),
                "status" to ReportStatus.PENDING.name,
                "reporterId" to userId
            )
            
            // 2. Submit the report document
            firestore.collection("reports").document(reportId).set(reportData).await()
            
            // 3. Increment report count on the artifact/comment for quick thresholding
            try {
                if (commentId != null) {
                    firestore.collection("comments").document(commentId)
                        .update("reportCount", FieldValue.increment(1))
                        .await()
                } else {
                    firestore.collection("artifacts").document(artifactId)
                        .update(
                            "reportCount", FieldValue.increment(1),
                            "safetyConcernCount", FieldValue.increment(1)
                        )
                        .await()
                }
            } catch (e: Exception) {
                Log.e("ArtifactRepository", "Report metadata update failed", e)
            }
                
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Report submission failed", e)
            Result.failure(AppError.from(e))
        }
    }

    suspend fun sendReply(artifactId: String, message: String): Result<Unit> {
        return try {
            val artifactDoc = firestore.collection("artifacts").document(artifactId).get().await()
            val artifactOwnerId = artifactDoc.getString("userId") ?: throw Exception("Owner not found")

            val replyRef = firestore.collection("artifacts").document(artifactId).collection("replies").document()
            val reply = Reply(id = replyRef.id, artifactId = artifactId, message = message, createdAt = Timestamp.now())
            
            firestore.runBatch { batch ->
                batch.set(replyRef, reply)
            }.await()

            notificationRepository.createNotification(
                userId = artifactOwnerId,
                message = "Someone replied to your artifact 💬",
                artifactId = artifactId
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    fun getArtifactsPager(emotion: String?): Flow<PagingData<Artifact>> {
        return Pager(
            config = PagingConfig(
                pageSize = 10,
                prefetchDistance = 2,
                initialLoadSize = 5,
                enablePlaceholders = false,
                maxSize = 30
            ),
            remoteMediator = ArtifactRemoteMediator(firestore, database, emotion),
            pagingSourceFactory = { artifactDao.getArtifactsPaged() }
        ).flow.map { pagingData ->
            pagingData.map { entity -> mapEntityToArtifact(entity) }
        }
    }

    private fun mapEntityToArtifact(entity: ArtifactEntity): Artifact {
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
                } catch (e: Exception) {
                    AvatarConfig(seed = entity.authorAvatarSeed)
                }
            ),
            audioUrl = entity.audioUrl,
            createdAt = com.google.firebase.Timestamp(java.util.Date(entity.createdAt)),
            durationMs = entity.durationMs,
            title = entity.title,
            description = entity.description,
            emotion = entity.emotion,
            emotionTag = entity.emotionTag,
            playCount = entity.playCount,
            reactionCount = entity.reactionCount,
            commentCount = entity.commentCount,
            amplitudeData = entity.amplitudeData,
            transcriptUrl = entity.transcriptUrl
        )
    }

    private fun mapToEntity(artifact: Artifact): ArtifactEntity {
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
            emotion = artifact.emotion,
            emotionTag = artifact.emotionTag,
            playCount = artifact.playCount,
            reactionCount = artifact.reactionCount,
            commentCount = artifact.commentCount,
            amplitudeData = artifact.amplitudeData,
            transcriptUrl = artifact.transcriptUrl,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Fetches a batch of raw candidates for client-side ranking.
     */
    suspend fun getCandidateArtifacts(limit: Long = 50): List<Artifact> = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
                .whereEqualTo("status", ArtifactStatus.ACTIVE.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
            
            withContext(Dispatchers.Default) {
                snapshot.documents
                    .mapNotNull { doc ->
                        val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                        
                        // Moderation Filter: Hide if HIDDEN or if reports are high (even if AI missed it)
                        val reportCount = doc.getLong("reportCount") ?: 0L
                        val modStatus = artifact?.moderation?.status ?: ModerationStatus.SAFE
                        
                        if (modStatus == ModerationStatus.HIDDEN || reportCount >= 5) {
                            null
                        } else {
                            // HARDENING: Slim artifacts before they hit the view layer
                            artifact?.slimForFeed()
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Error fetching candidates", e)
            emptyList()
        }
    }

    suspend fun recordPlay(userId: String?, emotion: String) {
        if (emotion.isEmpty()) return
        
        // 1. Persist locally for immediate personalization (AppSearch)
        personalizationEngine.get().recordInteraction(emotion)

        // 2. Persist to Firestore if authenticated
        if (userId == null) return
        try {
            val userRef = firestore.collection("users").document(userId)
            firestore.runTransaction { transaction ->
                val userDoc = transaction.get(userRef)
                if (userDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val currentPrefs = userDoc.get("emotionPreferences") as? Map<String, Long> ?: emptyMap()
                    val newCount = (currentPrefs[emotion] ?: 0L) + 1
                    val newPrefs = currentPrefs.toMutableMap().apply { put(emotion, newCount) }
                    transaction.update(userRef, "emotionPreferences", newPrefs)
                }
            }.await()
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Error recording play", e)
        }
    }

    /**
     * Fetches all artifacts liked by a specific user.
     * Uses the unified 'artifact_reactions' collection.
     */
    fun getLikedArtifacts(userId: String): Flow<List<Artifact>> = callbackFlow {
        var lastErrorTime = 0L
        val subscription = firestore.collection("artifact_reactions")
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorTime > 5000) { // 5s throttle
                        Log.e("ArtifactRepository", "Liked artifacts listener error: ${error.code}", error)
                        lastErrorTime = now
                    }
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val artifactIds = snapshot?.documents?.mapNotNull { it.getString("artifactId") } ?: emptyList()
                
                if (artifactIds.isEmpty()) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
// ...

                repositoryScope.launch(Dispatchers.IO) {
                    // Firestore 'whereIn' is limited to 10-30 items depending on version.
                    // For a production profile, we'd paginate or chunk this.
                    val chunks = artifactIds.chunked(10)
                    val allLiked = mutableListOf<Artifact>()
                    for (chunk in chunks) {
                        val docs = firestore.collection("artifacts")
                            .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                            .get().await()
                        
                        val mappedChunk = withContext(Dispatchers.Default) {
                            docs.toObjects(Artifact::class.java).mapIndexed { i, a ->
                                a.copy(id = docs.documents[i].id)
                            }
                        }
                        allLiked.addAll(mappedChunk)
                    }
                    trySend(allLiked.sortedByDescending { it.createdAt })
                }
            }
        awaitClose { subscription.remove() }
    }

    /**
     * Persists a private emotional bookmark for an artifact.
     * Stored strictly under the user's private collection.
     */
    suspend fun saveArtifact(
        userId: String,
        artifact: Artifact,
        shelf: String = "Stayed With Me"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val savedRef = firestore.collection("users").document(userId)
                .collection("savedArtifacts").document(artifact.id)
            
            val data = mapOf(
                "artifactId" to artifact.id,
                "savedAt" to FieldValue.serverTimestamp(),
                // Denormalized for quick list rendering in Saved tab
                "title" to artifact.title,
                "authorName" to artifact.author.name,
                "audioUrl" to artifact.audioUrl,
                "emotionTag" to artifact.emotionTag,
                "shelf" to shelf
            )
            
            savedRef.set(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Failed to preserve artifact ${artifact.id}", e)
            Result.failure(e)
        }
    }

    /**
     * Removes a private emotional bookmark.
     */
    suspend fun unsaveArtifact(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            firestore.collection("users").document(userId)
                .collection("savedArtifacts").document(artifactId)
                .delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Failed to unsave artifact $artifactId", e)
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
                val ids = snapshot?.documents?.map { it.id }?.toSet() ?: emptySet()
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
                            docs.toObjects(Artifact::class.java).mapIndexed { i, a ->
                                a.copy(id = docs.documents[i].id)
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

        val fileToUpload = originalFile
        val isTemp = false

        if (fileToUpload.length() == 0L) {
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

            while (currentRetry <= maxRetries) {
                try {
                    return@withContext withTimeout(60_000L * 5) { // 5-minute timeout
                        val uploadTask = if (draft.uploadSessionUri != null) {
                            fileRef.putFile(Uri.fromFile(fileToUpload), metadata, Uri.parse(draft.uploadSessionUri))
                        } else {
                            fileRef.putFile(Uri.fromFile(fileToUpload), metadata)
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
                                Log.w("ArtifactRepository", "Resumable session expired (HTTP $httpCode). Clearing URI and restarting.")
                                // Clear the invalid session URI in the DB via DAO
                                draftDao.updateSyncProgress(draft.id, 0, draft.totalBytes, null)
                                
                                // Restart without the session URI
                                fileRef.putFile(Uri.fromFile(fileToUpload), metadata).addOnProgressListener { snapshot ->
                                    launch {
                                        onProgress(snapshot.bytesTransferred, snapshot.totalByteCount, snapshot.uploadSessionUri)
                                    }
                                }.await()
                            } else {
                                throw e
                            }
                        }

                        // HARDENING: Retrieve downloadUrl from snapshot storage reference for better reliability
                        val downloadUrl = retryMetadataFetch(taskSnapshot.storage)
                            ?: return@withTimeout Result.failure(Exception("Upload succeeded but URL retrieval timed out. Check Firebase Storage rules and App Check status."))

                        Result.success(downloadUrl)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    
                    if (!isTransientError(e)) {
                        Log.e("ArtifactRepository", "Terminal upload failure: ${e.message}")
                        return@withContext Result.failure(e)
                    }

                    currentRetry++
                    if (currentRetry > maxRetries) {
                        Log.e("ArtifactRepository", "Max retries exceeded for transient error", e)
                        return@withContext Result.failure(e)
                    }

                    val delayTime = (2.0.pow(currentRetry.toDouble()).toLong() * 1000L)
                    Log.w("ArtifactRepository", "Upload attempt $currentRetry failed, retrying in $delayTime ms", e)
                    delay(delayTime)
                }
            }
            Result.failure(Exception("Max retries exceeded"))
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Resumable upload failed", e)
            Result.failure(e)
        } finally {
            if (isTemp) {
                Log.d("ArtifactRepository", "Cleaning up temporary decrypted file")
                fileToUpload.delete()
            }
        }
    }

    /**
     * Determines if an error is transient (retriable) or terminal.
     */
    fun isTransientError(e: Throwable): Boolean {
        return Companion.isTransientError(e)
    }

    companion object {
        fun isTransientError(e: Throwable): Boolean {
            return when (e) {
                is java.net.SocketTimeoutException,
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketException,
                is java.io.InterruptedIOException -> true
                is com.google.firebase.storage.StorageException -> {
                    when (e.errorCode) {
                        com.google.firebase.storage.StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
                        com.google.firebase.storage.StorageException.ERROR_NOT_AUTHENTICATED, // Often transient token issue
                        com.google.firebase.storage.StorageException.ERROR_QUOTA_EXCEEDED,
                        com.google.firebase.storage.StorageException.ERROR_NOT_AUTHORIZED -> true // Can be transient if token expired
                        else -> {
                            val httpCode = e.httpResultCode
                            httpCode == 408 || httpCode == 429 || httpCode >= 500
                        }
                    }
                }
                is com.google.firebase.firestore.FirebaseFirestoreException -> {
                    when (e.code) {
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE,
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED -> true
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * Hardening: Specifically retries the download URL fetch to handle eventual consistency.
     */
    private suspend fun retryMetadataFetch(ref: com.google.firebase.storage.StorageReference): String? {
        repeat(5) { attempt ->
            try {
                return ref.downloadUrl.await().toString()
            } catch (e: Exception) {
                Log.w("ArtifactRepository", "Metadata fetch attempt ${attempt + 1} failed, retrying...")
                delay(1000L * (attempt + 1))
            }
        }
        return null
    }

    fun calculateChecksum(filePath: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val file = File(filePath)
            if (!file.exists()) return ""
            val inputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Checksum calculation failed", e)
            ""
        }
    }

    suspend fun createArtifactDocument(
        userId: String,
        username: String,
        audioUrl: String,
        draft: ArtifactDraftEntity,
        avatarSeed: String = "",
        avatarColor: String = "#FFD700",
        avatarConfig: AvatarConfig = AvatarConfig(),
        anonymousId: String = "",
        anonymousSigil: String = "",
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        isPublic: Boolean = true,
        transcriptUrl: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // 1. Recover Transcript from Frozen Snapshot
            val transcript = draft.frozenTranscriptJson?.let { json ->
                try {
                    kotlinx.serialization.json.Json.decodeFromString<List<TranscriptSegment>>(json)
                } catch (e: Exception) {
                    Log.e("ArtifactRepository", "Failed to decode frozen transcript", e)
                    emptyList()
                }
            } ?: emptyList()

            val artifact = Artifact(
                id = draft.id, // IDEMPOTENCY: Use draftId as the Firestore Document ID
                userId = userId,
                author = AuthorSnapshot(
                    anonymousId = anonymousId,
                    name = username,
                    sigil = anonymousSigil,
                    avatarSeed = avatarSeed,
                    avatarColor = avatarColor,
                    avatarConfig = avatarConfig
                ),
                audioUrl = audioUrl,
                createdAt = Timestamp.now(),
                isPublic = isPublic,
                visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
                status = status,
                isDraft = false,
                durationMs = draft.durationMs,
                title = draft.title ?: "Untitled Artifact",
                description = draft.description ?: "",
                emotion = draft.emotion ?: "",
                emotionTag = draft.emotion ?: "",
                prompt = "",
                transcript = transcript,
                transcriptUrl = transcriptUrl,
                amplitudeData = draft.amplitudeData,
                reactionVisibility = draft.reactionVisibility ?: ReactionVisibilityMode.APPROXIMATE,
                moderation = ModerationMetadata(
                    status = ModerationStatus.SAFE,
                    updatedAt = Timestamp.now()
                )
            )
            val artifactData = mapArtifactToFirestore(artifact)
            
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
            
            if (status == ArtifactStatus.ACTIVE) {
                // Create in-app notification for the user
                notificationRepository.createNotification(
                    userId = userId,
                    message = "You published a new artifact: ${artifact.title} ✨",
                    artifactId = draft.id
                )
            }

            Result.success(draft.id)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Firestore write failed", e)
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
                "isPublic" to isPublic,
                "visibility" to if (isPublic) Visibility.PUBLIC.name else Visibility.PRIVATE.name
            )
            transcriptUrl?.let { updates["transcriptUrl"] = it }

            firestore.collection("artifacts").document(artifactId)
                .update(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Failed to finalize artifact $artifactId", e)
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

            fileRef.putBytes(transcriptJson.toByteArray(), metadata).await()
            val downloadUrl = fileRef.downloadUrl.await().toString()
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Transcript upload failed", e)
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
            Log.e("ArtifactRepository", "Failed to fetch transcript from $url", e)
            Result.failure(e)
        }
    }

    suspend fun deleteAllUserData(userId: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Delete Firestore artifacts and associated data
            val artifacts = firestore.collection("artifacts")
                .whereEqualTo("userId", userId)
                .get()
                .await()

            for (doc in artifacts.documents) {
                val artifactId = doc.id
                val audioUrl = doc.getString("audioUrl")
                
                // 1a. Delete storage file
                if (audioUrl != null) deleteStorageFile(audioUrl)

                // 1b. Delete associated reactions
                val reactionsGlobal = firestore.collection("reactions_global").whereEqualTo("artifactId", artifactId).get().await()
                reactionsGlobal.documents.forEach { it.reference.delete().await() }

                val reactions = firestore.collection("reactions").whereEqualTo("artifactId", artifactId).get().await()
                reactions.documents.forEach { it.reference.delete().await() }

                // 1c. Delete associated comments
                val artifactComments = firestore.collection("comments").whereEqualTo("artifactId", artifactId).get().await()
                artifactComments.documents.forEach { it.reference.delete().await() }
                
                // 1d. Delete reaction counts
                firestore.collection("artifact_reaction_counts").document(artifactId).delete().await()

                // 1e. Delete the artifact itself
                doc.reference.delete().await()
            }

            // 2. Delete Profile Picture if exists
            val userDoc = firestore.collection("users").document(userId).get().await()
            val profilePicUrl = userDoc.getString("profilePictureUrl")
            if (profilePicUrl != null) deleteStorageFile(profilePicUrl)

            // 3. Delete user sub-collections (Best effort for orphaning)
            val userRef = firestore.collection("users").document(userId)
            
            val resonanceOut = userRef.collection("resonance_out").get().await()
            resonanceOut.documents.forEach { it.reference.delete().await() }

            val resonanceIn = userRef.collection("resonance_in").get().await()
            resonanceIn.documents.forEach { it.reference.delete().await() }
            
            val following = userRef.collection("following").get().await()
            following.documents.forEach { it.reference.delete().await() }
            
            val followers = userRef.collection("followers").get().await()
            followers.documents.forEach { it.reference.delete().await() }
            
            val savedArtifacts = userRef.collection("savedArtifacts").get().await()
            savedArtifacts.documents.forEach { it.reference.delete().await() }

            // 4. Delete Username reservation
            val usernameDocs = firestore.collection("usernames").whereEqualTo("uid", userId).get().await()
            usernameDocs.documents.forEach { it.reference.delete().await() }

            // 5. Delete Notifications
            val notifications = firestore.collection("notifications").whereEqualTo("userId", userId).get().await()
            notifications.documents.forEach { it.reference.delete().await() }

            // 6. Delete Firestore drafts
            val drafts = firestore.collection("drafts")
                .whereEqualTo("userId", userId)
                .get()
                .await()
            for (doc in drafts.documents) {
                doc.reference.delete().await()
            }

            // 7. Delete comments by this user
            val userComments = firestore.collection("comments")
                .whereEqualTo("authorId", userId)
                .get()
                .await()
            for (doc in userComments.documents) {
                doc.reference.delete().await()
            }

            // 8. Delete local drafts
            draftDao.deleteAll()

        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Error during full data cleanup", e)
            throw e
        }
    }

    private suspend fun deleteStorageFile(url: String) {
        if (!url.contains("firebasestorage")) return
        try {
            storage.getReferenceFromUrl(url).delete().await()
        } catch (e: Exception) {
            val isNotFound = e is com.google.firebase.storage.StorageException && 
                e.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND
            
            if (isNotFound) {
                Log.w("ArtifactRepository", "Storage file already gone, treating as success: $url")
            } else {
                Log.e("ArtifactRepository", "Failed to delete storage file: $url", e)
                throw e // Re-throw if it's a real error (e.g. network)
            }
        }
    }

    /**
     * Renames a published artifact in Firestore.
     */
    suspend fun renamePublishedArtifact(artifactId: String, newTitle: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            firestore.collection("artifacts").document(artifactId)
                .update("title", newTitle)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Rename failed", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes a published artifact from Firestore and removes the audio file from Storage.
     * Hardening: Firestore anchor is deleted FIRST. If successful, the Cloud Function
     * 'onArtifactDeleted' will handle cascading cleanup of reactions, comments, and storage.
     * The client attempts a best-effort storage deletion here for immediate recovery.
     */
    suspend fun deletePublishedArtifact(artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val doc = artifactRef.get().await()
            
            if (!doc.exists()) {
                Log.w("ArtifactRepository", "Artifact $artifactId already deleted from Firestore")
                return@withContext Result.success(Unit)
            }
            
            val audioUrl = doc.getString("audioUrl")
            
            // 1. Delete Firestore anchor first (Authoritative state change)
            artifactRef.delete().await()
            Log.d("ArtifactRepository", "Artifact anchor $artifactId deleted. Cloud Function will handle cascading cleanup.")

            // 2. Immediate best-effort Storage cleanup
            try {
                if (audioUrl != null) deleteStorageFile(audioUrl)
            } catch (e: Exception) {
                // Log but don't fail; Cloud Function or eventual cleanup will catch this
                Log.e("ArtifactRepository", "Cleanup: Immediate storage deletion failed (best-effort)", e)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Critical failure during deletion for $artifactId", e)
            Result.failure(e)
        }
    }
}
