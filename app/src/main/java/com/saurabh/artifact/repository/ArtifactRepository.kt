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
import com.saurabh.artifact.security.SecurityArchitecture
import com.saurabh.artifact.service.ReflectionAIService
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SafetyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("SameParameterValue")
@Singleton
class ArtifactRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val draftDao: DraftDao,
    private val localDraftManager: com.saurabh.artifact.audio.LocalDraftManager,
    private val aiService: dagger.Lazy<ReflectionAIService>,
    private val safetyEvaluator: dagger.Lazy<SafetyEvaluator>,
    private val personalizationEngine: dagger.Lazy<com.saurabh.artifact.service.PersonalizationEngine>
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    suspend fun finalizePublish(draftId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure(Exception("Draft not found"))
            
            // 1. Ensure we have an authenticated user
            val userId = firestore.app.get(com.google.firebase.auth.FirebaseAuth::class.java).currentUser?.uid 
                ?: return@withContext Result.failure(Exception("Unauthenticated user"))

            // 2. Resumable Upload to Firebase Storage
            val audioUrl = uploadArtifactResumable(userId, draft).getOrNull()
                ?: return@withContext Result.failure(Exception("Failed to upload audio to storage"))

            // 3. Create Firestore Artifact Document
            // Note: We use the returned audioUrl which is the verified download link
            createArtifactDocument(
                userId = userId,
                username = "Anonymous Soul", // Fallback, should be from UserProfileManager
                audioUrl = audioUrl,
                draft = draft
            ).onSuccess {
                // 4. Cleanup local draft on success
                deleteDraftLocally(draftId)
            }.onFailure { e ->
                Log.e("ArtifactRepository", "Firestore creation failed for draft $draftId", e)
                return@withContext Result.failure(e)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Finalize publish failed", e)
            Result.failure(e)
        }
    }


    /**
     * Fetches a smart, context-aware reflection prompt with real-time safety evaluation.
     * Prioritizes safety overrides for high-risk emotional signals.
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
                aiResponse.copy(question = safeText)
            } else {
                // 3. Fallback based on safety level
                if ((safetyResult.level == SafetyLevel.MEDIUM) && (safetyResult.suggestedPrompt != null)) {
                    safetyResult.suggestedPrompt
                } else {
                    ReflectionPromptProvider.getRandomPrompt()
                }
            }
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "AI prompt generation failed, falling back", e)
            if ((safetyResult.level == SafetyLevel.MEDIUM) && (safetyResult.suggestedPrompt != null)) {
                safetyResult.suggestedPrompt
            } else {
                ReflectionPromptProvider.getRandomPrompt()
            }
        }
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
        userEmoji: String = "✨",
        avatarColor: String = "#FFD700",
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

            fileRef.putFile(uploadUri)
                .addOnProgressListener { taskSnapshot ->
                    val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount)
                    onProgress(progress.toFloat())
                }.await()

            val downloadUrl = fileRef.downloadUrl.await().toString()

            val artifact = Artifact(
                userId = userId,
                username = username,
                avatarColor = avatarColor,
                audioUrl = downloadUrl,
                createdAt = Timestamp.now(),
                isPublic = isPublic,
                visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
                isDraft = false,
                duration = duration,
                title = title,
                emotion = emotion,
                emotionTag = emotionTag,
                emotionConfidence = emotionConfidence,
                prompt = prompt,
                userEmoji = userEmoji,
                moderation = ModerationMetadata(
                    status = ModerationStatus.SAFE, // Initial state, will be updated by Cloud Function
                    updatedAt = Timestamp.now()
                )
            )

            try {
                val artifactData = mapOf(
                    "userId" to artifact.userId,
                    "username" to artifact.username,
                    "avatarColor" to artifact.avatarColor,
                    "audioUrl" to artifact.audioUrl,
                    "createdAt" to artifact.createdAt,
                    "isPublic" to artifact.isPublic,
                    "visibility" to artifact.visibility.name,
                    "isDraft" to artifact.isDraft,
                    "duration" to artifact.duration,
                    "title" to artifact.title,
                    "emotion" to artifact.emotion,
                    "emotionTag" to artifact.emotionTag,
                    "emotionConfidence" to artifact.emotionConfidence,
                    "prompt" to artifact.prompt,
                    "redactionFilter" to redactionFilter,
                    "userEmoji" to artifact.userEmoji,
                    "amplitudeData" to amplitudeData,
                    "reactionVisibility" to reactionVisibility.name,
                    "moderation" to mapOf(
                        "status" to artifact.moderation.status.name,
                        "score" to artifact.moderation.score,
                        "updatedAt" to artifact.moderation.updatedAt
                    )
                )
                firestore.collection("artifacts").add(artifactData).await()
                Result.success(Unit)
            } catch (firestoreEx: Exception) {
                // HARDENING: Clean up the orphaned file in Storage if Firestore entry fails
                Log.e("ArtifactRepository", "Firestore write failed, deleting orphaned file", firestoreEx)
                fileRef.delete().await()
                Result.failure(firestoreEx)
            }
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Upload failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadAudioComment(
        artifactId: String,
        localFilePath: String,
        userId: String,
        authorName: String = "Anonymous Soul",
        authorEmoji: String = "✨"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            // 1. Upload to Storage
            val fileUri = Uri.fromFile(File(localFilePath))
            val storageRef = storage.reference.child("comments/$artifactId/${UUID.randomUUID()}.m4a")
            storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()

            // 2. Atomic Transaction to update Artifact
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            
            firestore.runTransaction { transaction ->
                val snapshot = transaction[artifactRef]
                if (!snapshot.exists()) throw Exception("Artifact not found")

                val newComment = mapOf(
                    "id" to UUID.randomUUID().toString(),
                    "authorId" to userId,
                    "authorName" to authorName,
                    "authorEmoji" to authorEmoji,
                    "audioUrl" to downloadUrl,
                    "createdAt" to Timestamp.now()
                )

                // Append to the comments array safely
                transaction.update(artifactRef, "comments", FieldValue.arrayUnion(newComment))
                transaction.update(artifactRef, "commentCount", FieldValue.increment(1))
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Comment upload failed", e)
            Result.failure(e)
        }
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
                VoiceComment(
                    id = map["id"] as? String ?: "",
                    authorId = map["authorId"] as? String ?: "",
                    authorName = map["authorName"] as? String ?: "",
                    authorEmoji = map["authorEmoji"] as? String ?: "",
                    audioUrl = map["audioUrl"] as? String ?: "",
                    createdAt = map["createdAt"] as? Timestamp ?: Timestamp.now()
                )
            } ?: emptyList()
        )
    }

    suspend fun getArtifactById(artifactId: String): Artifact? = withContext(Dispatchers.IO) {
        return@withContext try {
            val doc = firestore.collection("artifacts").document(artifactId).get().await()
            if (doc.exists()) {
                doc.toObject(Artifact::class.java)?.copy(id = doc.id)
            } else null
        } catch (e: Exception) {
            null
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
            Log.e("ArtifactRepository", "Error fetching reports", e)
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
                
                if (action == ModerationAction.HIDE_ARTIFACT) {
                    transaction.update(artifactRef, "moderation.status", ModerationStatus.HIDDEN.name)
                    transaction.update(artifactRef, "isPublic", false)
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
     * Executes a privacy-first reaction update.
     * Total counts are updated, but per-type counts are hidden from public view
     * to prevent popularity loops and maintain a non-addictive experience.
     */
    suspend fun reactToArtifact(
        artifactId: String,
        userId: String,
        type: ReactionType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val reactionId = "${userId}_$artifactId"
        val reactionRef = firestore.collection("reactions").document(reactionId)
        val artifactRef = firestore.collection("artifacts").document(artifactId)

        return@withContext try {
            firestore.runTransaction { transaction ->
                val existingDoc = transaction.get(reactionRef)
                val artifactDoc = transaction.get(artifactRef)
                
                if (!artifactDoc.exists()) throw Exception("Artifact not found")
                val artifact = artifactDoc.toObject(Artifact::class.java)!!

                val currentTotalCount = artifactDoc.getLong("reactionCount") ?: 0L
                
                if (existingDoc.exists()) {
                    val oldTypeName = existingDoc.getString("type")
                    if (oldTypeName == type.name) {
                        // Undo reaction (Toggle off)
                        transaction.delete(reactionRef)
                        transaction.update(artifactRef, "reactionCount", (currentTotalCount - 1).coerceAtLeast(0))
                        
                        // Also cleanup global reference for liked tab
                        val globalRef = firestore.collection("reactions_global").document(reactionId)
                        transaction.delete(globalRef)
                    } else {
                        // Change reaction type (Count stays same)
                        transaction.update(reactionRef, "type", type.name)
                        transaction.update(reactionRef, "updatedAt", FieldValue.serverTimestamp())
                    }
                } else {
                    // New reaction
                    val reactionData = mapOf(
                        "userId" to userId,
                        "artifactId" to artifactId,
                        "type" to type.name,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    transaction.set(reactionRef, reactionData)
                    transaction.update(artifactRef, "reactionCount", currentTotalCount + 1)

                    // Top-level reference for "My Reactions" / Liked tab
                    val globalRef = firestore.collection("reactions_global").document(reactionId)
                    transaction.set(globalRef, mapOf(
                        "userId" to userId,
                        "artifactId" to artifactId,
                        "timestamp" to FieldValue.serverTimestamp()
                    ))

                    // Trigger Empathetic Notification (Only if not by the author)
                    if (artifact.userId != userId) {
                        val notificationRef = firestore.collection("notifications").document()
                        val notification = NotificationItem(
                            id = notificationRef.id,
                            userId = artifact.userId,
                            message = getEmpatheticMessage(type),
                            artifactId = artifactId,
                            createdAt = Timestamp.now()
                        )
                        transaction.set(notificationRef, notification)
                    }
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Reaction failed", e)
            Result.failure(e)
        }
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
        deviceId: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val report = UserReport(
                id = UUID.randomUUID().toString(),
                artifactId = artifactId,
                reporterDeviceId = deviceId,
                reason = reason,
                details = details,
                createdAt = Timestamp.now(),
                status = ReportStatus.PENDING
            )
            
            val reportData = mapOf(
                "artifactId" to report.artifactId,
                "reporterDeviceId" to report.reporterDeviceId,
                "reason" to report.reason.name,
                "details" to report.details,
                "createdAt" to report.createdAt,
                "status" to report.status.name
            )
            
            firestore.collection("reports").document(report.id).set(reportData).await()
            
            // Also increment report count on the artifact for quick thresholding
            firestore.collection("artifacts").document(artifactId)
                .update("reportCount", FieldValue.increment(1))
                .await()
                
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Report submission failed", e)
            Result.failure(e)
        }
    }

    private fun getEmpatheticMessage(type: ReactionType): String {
        return when (type) {
            ReactionType.I_HEAR_YOU -> "Someone is listening to your heart 👂"
            ReactionType.RELATABLE -> "Someone found your words relatable 🐚"
            ReactionType.SENDING_STRENGTH -> "Someone sent you strength 💫"
            ReactionType.STAY_STRONG -> "Someone wants you to stay strong 🕯️"
            ReactionType.HOLDING_SPACE -> "Someone is holding space for you 🕯️"
            ReactionType.THANK_YOU -> "Someone is grateful you shared your voice 🙏"
            ReactionType.FELT_DEEPLY -> "Someone felt your words deeply 🌊"
            ReactionType.RESPECTFUL_DISAGREEMENT -> "Someone respectfully sees things differently 🧘"
        }
    }

    suspend fun sendReply(artifactId: String, message: String): Result<Unit> {
        return try {
            val artifactDoc = firestore.collection("artifacts").document(artifactId).get().await()
            val artifactOwnerId = artifactDoc.getString("userId") ?: throw Exception("Owner not found")

            val replyRef = firestore.collection("artifacts").document(artifactId).collection("replies").document()
            val reply = Reply(id = replyRef.id, artifactId = artifactId, message = message, createdAt = Timestamp.now())
            
            val notificationRef = firestore.collection("notifications").document()
            val notification = NotificationItem(
                id = notificationRef.id,
                userId = artifactOwnerId,
                message = "Someone replied to your artifact 💬",
                artifactId = artifactId,
                createdAt = Timestamp.now()
            )

            firestore.runBatch { batch ->
                batch.set(replyRef, reply)
                batch.set(notificationRef, notification)
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listenNotifications(userId: String): Flow<List<NotificationItem>> = callbackFlow {
        var lastErrorTime = 0L
        val subscription = firestore.collection("notifications")
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorTime > 5000) { // 5s throttle for errors
                        Log.e("ArtifactRepository", "Notification listener error: ${error.code}", error)
                        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                            Log.e("ArtifactRepository", "MISSING INDEX: composite index on notifications(userId, createdAt) required.")
                        }
                        lastErrorTime = now
                    }
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                repositoryScope.launch(Dispatchers.Default) {
                    val notifications = snapshot?.documents?.mapNotNull { 
                        it.toObject(NotificationItem::class.java)?.copy(id = it.id)
                    } ?: emptyList()
                    trySend(notifications)
                }
            }
        awaitClose { subscription.remove() }
    }

    suspend fun markNotificationAsRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            firestore.collection("notifications").document(notificationId)
                .update("isRead", true)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getArtifactsPager(emotion: String?): Flow<PagingData<Artifact>> {
        return Pager(
            config = PagingConfig(
                pageSize = 10,
                prefetchDistance = 2,
                initialLoadSize = 5,
                enablePlaceholders = false,
                maxSize = 30 // Ensure old pages are evicted from memory
            ),
            pagingSourceFactory = { ArtifactPagingSource(firestore, emotion) }
        ).flow
    }

    /**
     * Fetches a batch of raw candidates for client-side ranking.
     */
    suspend fun getCandidateArtifacts(limit: Long = 50): List<Artifact> = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
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
     * Uses the top-level 'reactions_global' collection for efficient querying.
     */
    fun getLikedArtifacts(userId: String): Flow<List<Artifact>> = callbackFlow {
        var lastErrorTime = 0L
        val subscription = firestore.collection("reactions_global")
            .whereEqualTo("userId", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorTime > 5000) { // 5s throttle
                        Log.e("ArtifactRepository", "Liked artifacts listener error: ${error.code}", error)
                        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            Log.e("ArtifactRepository", "PERMISSION DENIED: check security rules for reactions_global.")
                        }
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

    suspend fun uploadArtifactResumable(
        userId: String,
        draft: ArtifactDraftEntity,
        onProgress: suspend (Long, Long, Uri?) -> Unit = { _, _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val originalFile = File(draft.localAudioPath)
            if (!originalFile.exists()) return@withContext Result.failure(Exception("File missing"))

            // HARDENING: Handle Encryption - Decrypt if necessary before upload
            val (fileToUpload, isTemp) = if (draft.isEncrypted) {
                try {
                    val decryptedFile = SecurityArchitecture.createSecureTempFile(context, ".m4a")
                    localDraftManager.getEncryptedInputStream(originalFile).use { input ->
                        decryptedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d("ArtifactRepository", "Decrypted encrypted draft for upload: ${decryptedFile.length()} bytes")
                    decryptedFile to true
                } catch (e: Exception) {
                    Log.e("ArtifactRepository", "Failed to decrypt draft for upload, attempting raw upload", e)
                    originalFile to false
                }
            } else {
                originalFile to false
            }

            if (fileToUpload.length() == 0L) {
                if (isTemp) fileToUpload.delete()
                return@withContext Result.failure(Exception("File is empty, aborting upload"))
            }

            val fileName = "artifacts/${userId}_${draft.id}.m4a"
            val fileRef = storage.reference.child(fileName)
            
            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("draftId", draft.id)
                .setCustomMetadata("checksum", draft.checksum ?: "")
                .build()

            try {
                val uploadTask = if (draft.uploadSessionUri != null) {
                    fileRef.putFile(Uri.fromFile(fileToUpload), metadata, Uri.parse(draft.uploadSessionUri))
                } else {
                    fileRef.putFile(Uri.fromFile(fileToUpload), metadata)
                }

                uploadTask.addOnProgressListener { taskSnapshot ->
                    launch {
                        onProgress(taskSnapshot.bytesTransferred, taskSnapshot.totalByteCount, taskSnapshot.uploadSessionUri)
                    }
                }.await()

                val downloadUrl = fileRef.downloadUrl.await().toString()
                Result.success(downloadUrl)
            } finally {
                if (isTemp) {
                    Log.d("ArtifactRepository", "Cleaning up temporary decrypted file")
                    fileToUpload.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Resumable upload failed", e)
            Result.failure(e)
        }
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
        userEmoji: String = "✨",
        avatarColor: String = "#FFD700"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val artifactData = mapOf(
                "userId" to userId,
                "username" to username,
                "avatarColor" to avatarColor,
                "audioUrl" to audioUrl,
                "createdAt" to Timestamp.now(),
                "isPublic" to true,
                "visibility" to Visibility.PUBLIC.name,
                "isDraft" to false,
                "duration" to draft.durationMs / 1000,
                "title" to (draft.title ?: "Untitled Artifact"),
                "emotion" to (draft.emotion ?: ""),
                "emotionTag" to (draft.emotion ?: ""),
                "prompt" to "",
                "userEmoji" to userEmoji,
                "reactionVisibility" to (draft.reactionVisibility ?: ReactionVisibilityMode.APPROXIMATE).name,
                "moderation" to mapOf(
                    "status" to ModerationStatus.SAFE.name,
                    "score" to 0.0,
                    "updatedAt" to Timestamp.now()
                )
            )
            firestore.collection("artifacts").add(artifactData).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Firestore write failed", e)
            Result.failure(e)
        }
    }

    suspend fun deleteAllUserData(userId: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Delete Firestore artifacts and associated storage files
            val artifacts = firestore.collection("artifacts")
                .whereEqualTo("userId", userId)
                .get()
                .await()

            for (doc in artifacts.documents) {
                val audioUrl = doc.getString("audioUrl")
                if (audioUrl?.contains("firebasestorage") == true) {
                    try {
                        storage.getReferenceFromUrl(audioUrl).delete().await()
                    } catch (e: Exception) {
                        Log.e("ArtifactRepository", "Failed to delete storage file: $audioUrl", e)
                    }
                }
                doc.reference.delete().await()
            }

            // 2. Delete Profile Picture if exists
            val userDoc = firestore.collection("users").document(userId).get().await()
            val profilePicUrl = userDoc.getString("profilePictureUrl")
            if (profilePicUrl?.contains("firebasestorage") == true) {
                try {
                    storage.getReferenceFromUrl(profilePicUrl).delete().await()
                } catch (e: Exception) {
                    Log.e("ArtifactRepository", "Failed to delete profile picture", e)
                }
            }

            // 3. Delete Firestore drafts
            val drafts = firestore.collection("drafts")
                .whereEqualTo("userId", userId)
                .get()
                .await()
            for (doc in drafts.documents) {
                doc.reference.delete().await()
            }

            // 4. Delete comments by this user
            val comments = firestore.collection("comments")
                .whereEqualTo("authorId", userId)
                .get()
                .await()
            for (doc in comments.documents) {
                doc.reference.delete().await()
            }

            // 5. Delete local drafts
            draftDao.deleteAll()

        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Error during full data cleanup", e)
            throw e
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
     */
    suspend fun deletePublishedArtifact(artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val doc = artifactRef.get().await()
            
            if (!doc.exists()) return@withContext Result.failure(Exception("Artifact not found"))
            
            val audioUrl = doc.getString("audioUrl")
            
            // 1. Delete from Storage first
            if (audioUrl != null) {
                try {
                    storage.getReferenceFromUrl(audioUrl).delete().await()
                } catch (e: Exception) {
                    Log.w("ArtifactRepository", "Storage file deletion failed or file already gone: $audioUrl", e)
                }
            }

            // 2. Delete Firestore document (this will NOT delete subcollections automatically, 
            // but for comments we are using an array in this schema, or a subcollection in others.
            // Based on mapDocumentToArtifactDetail, comments are in an array.)
            artifactRef.delete().await()
            
            // 3. Delete from reactions_global if needed (though typically handled by Cloud Functions for consistency)
            // But for immediate consistency:
            val reactions = firestore.collection("reactions_global").whereEqualTo("artifactId", artifactId).get().await()
            firestore.runBatch { batch ->
                reactions.documents.forEach { batch.delete(it.reference) }
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Deletion failed", e)
            Result.failure(e)
        }
    }
}
