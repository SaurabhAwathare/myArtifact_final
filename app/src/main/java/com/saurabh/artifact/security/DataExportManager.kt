package com.saurabh.artifact.security

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.saurabh.artifact.data.local.ArtifactDao
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.remote.model.CommentDto
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactLibraryRepository
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.util.EncryptedStorageManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataExportManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftDao: Lazy<DraftDao>,
    private val authRepository: AuthRepository,
    private val artifactRepository: Lazy<ArtifactRepository>,
    private val userRepository: Lazy<UserRepository>,
    private val libraryRepository: Lazy<ArtifactLibraryRepository>,
    private val encryptedStorageManager: EncryptedStorageManager,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val diagnosticLogger: DiagnosticLogger
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Creates a portable, human-readable ZIP archive of all user data and recordings.
     */
    suspend fun exportData(
        outputUri: Uri,
        onProgress: suspend (ExportProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onProgress(ExportProgress.Starting)
            // 1. Immutable Identity Capture
            val exportUserId = authRepository.currentUserId
            if (exportUserId.isEmpty()) return@withContext Result.failure(Exception("Unauthenticated export attempt"))

            val errors = mutableListOf<String>()
            
            context.contentResolver.openOutputStream(outputUri)?.use { rawOutputStream: OutputStream ->
                ZipOutputStream(rawOutputStream).use { zipOut ->
                    // Helper to verify session integrity before each phase
                    fun ensureSessionIntegrity() {
                        if (authRepository.currentUserId != exportUserId) {
                            throw IllegalStateException("Account switch detected during export. Aborting for privacy.")
                        }
                    }

                    // 2. Export README and Manifest (Placeholder)
                    zipOut.addTextEntry("README.txt", "Artifact Personal Data Export\nGenerated at: ${java.util.Date()}\n\nThis archive contains your personal reflections, voice recordings, and participation history.")

                    // 3. Export Profile (Fixed Identity)
                    ensureSessionIntegrity()
                    onProgress(ExportProgress.Profile)
                    exportProfile(exportUserId, zipOut, errors)

                    // 4. Export Artifacts (Metadata + Audio)
                    ensureSessionIntegrity()
                    val artifacts = exportArtifacts(exportUserId, zipOut, errors, onProgress)

                    // 5. Export Drafts (Metadata + Audio)
                    ensureSessionIntegrity()
                    val drafts = exportDrafts(exportUserId, zipOut, errors, onProgress)

                    // 6. Export Participation (Comments)
                    ensureSessionIntegrity()
                    onProgress(ExportProgress.Participation)
                    val commentsAuthored = exportAuthoredComments(exportUserId, zipOut, errors)
                    val commentsReceived = exportReceivedComments(exportUserId, artifacts, zipOut, errors)
                    val engagementCount = exportEngagement(exportUserId, zipOut, errors)

                    // 7. Export Resonance (Relationships)
                    ensureSessionIntegrity()
                    onProgress(ExportProgress.Resonance)
                    exportResonance(exportUserId, zipOut, errors)

                    // 8. Export Saved Content
                    ensureSessionIntegrity()
                    onProgress(ExportProgress.Saved)
                    exportSavedArtifacts(exportUserId, zipOut, errors)

                    // 8.5 Export Safety (Authored Reports)
                    ensureSessionIntegrity()
                    onProgress(ExportProgress.Safety)
                    val reportsCount = exportAuthoredReports(exportUserId, zipOut, errors)

                    // 9. Final Manifest
                    ensureSessionIntegrity()
                    onProgress(ExportProgress.Finalizing)
                    val manifest = ExportManifest(
                        exportedAt = java.util.Date().toString(),
                        artifactCount = artifacts.size,
                        draftCount = drafts.size,
                        commentAuthoredCount = commentsAuthored,
                        commentReceivedCount = commentsReceived,
                        engagementCount = engagementCount,
                        reportsAuthoredCount = reportsCount,
                        errors = errors
                    )
                    zipOut.addJsonEntry("manifest.json", manifest)

                    zipOut.finish()
                }
            } ?: throw IllegalStateException("Could not open output stream for URI: $outputUri")
            
            onProgress(ExportProgress.Complete(hasOmissions = errors.isNotEmpty()))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SETTINGS, "EXPORT_FATAL_ERROR", throwable = e)
            onProgress(ExportProgress.Failed(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    private suspend fun exportProfile(userId: String, zipOut: ZipOutputStream, errors: MutableList<String>) {
        try {
            val profile = userRepository.get().getCachedProfile(userId)
            if (profile != null) {
                val export = UserIdentityExport(
                    anonymousId = profile.anonymousId,
                    anonymousName = profile.anonymousName,
                    anonymousSigil = profile.anonymousSigil,
                    sigilConfig = profile.sigilConfig,
                    bio = profile.bio,
                    resonanceInCount = profile.resonanceInCount,
                    resonanceOutCount = profile.resonanceOutCount,
                    artifactsCount = profile.artifactsCount
                )
                zipOut.addJsonEntry("Profile/identity.json", export)
            }
        } catch (e: Exception) {
            errors.add("Failed to export profile: ${e.message}")
        }
    }

    private suspend fun exportArtifacts(
        userId: String,
        zipOut: ZipOutputStream,
        errors: MutableList<String>,
        onProgress: suspend (ExportProgress) -> Unit
    ): List<Artifact> {
        val allArtifacts = mutableListOf<Artifact>()
        var lastVisible: com.google.firebase.firestore.DocumentSnapshot? = null
        
        // 1. Get total count for progress reporting (if available)
        val profile = userRepository.get().getCachedProfile(userId)
        val totalExpected = profile?.artifactsCount?.toInt() ?: 0

        try {
            while (true) {
                // 2. Privacy Boundary: Abort immediately if account switched
                if (authRepository.currentUserId != userId) {
                    throw IllegalStateException("Account switch detected during export. Aborting for privacy.")
                }

                val result = artifactRepository.get().getUserArtifactsPage(userId, limit = 1000, lastVisible = lastVisible)
                val pair = result.getOrThrow()
                val pageArtifacts = pair.first
                lastVisible = pair.second
                
                if (pageArtifacts.isEmpty()) break

                pageArtifacts.forEach { artifact ->
                    val currentCount = allArtifacts.size + 1
                    onProgress(ExportProgress.Artifacts(currentCount, totalExpected.coerceAtLeast(currentCount)))
                    
                    val safeTitle = sanitizeFilename(artifact.title).ifBlank { "Untitled" }
                    val dirName = "Artifacts/${formatDate(artifact.createdAt)}_${safeTitle}_${artifact.id.take(5)}"
                    
                    val metadata = ArtifactExportMetadata(
                        id = artifact.id,
                        title = artifact.title,
                        description = artifact.description,
                        createdAt = artifact.createdAt.toDate().toString(),
                        emotion = artifact.emotion,
                        emotionTag = artifact.emotionTag,
                        durationMs = artifact.durationMs,
                        visibility = artifact.visibility.name,
                        playCount = artifact.playCount,
                        reactionCount = artifact.reactionCount,
                        commentCount = artifact.commentCount,
                        transcript = artifact.transcript
                    )
                    zipOut.addJsonEntry("$dirName/metadata.json", metadata)
                    
                    // Try Local Audio First
                    val localDraft = draftDao.get().getDraftByArtifactId(artifact.id, userId)
                    val audioFile = localDraft?.let { File(it.localAudioPath) }
                    
                    if (audioFile?.exists() == true) {
                        zipOut.addFileEntry("$dirName/audio.m4a", audioFile, decrypt = localDraft.isEncrypted)
                    } else if (artifact.audioUrl.isNotEmpty()) {
                        zipOut.addRemoteAudioEntry("$dirName/audio.m4a", artifact.audioUrl, errors)
                    } else {
                        errors.add("Audio missing for artifact: ${artifact.id}")
                    }
                    
                    allArtifacts.add(artifact)
                }

                if (lastVisible == null) break
            }
            return allArtifacts
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SETTINGS, "EXPORT_ARTIFACTS_FETCH_FAILED", throwable = e)
            errors.add("Failed to fetch artifacts: ${e.message}")
            // Re-throw account switch to terminate service cleanly
            if (e is IllegalStateException && e.message?.contains("Account switch") == true) throw e
            return allArtifacts
        }
    }

    private suspend fun exportDrafts(
        userId: String,
        zipOut: ZipOutputStream,
        errors: MutableList<String>,
        onProgress: suspend (ExportProgress) -> Unit
    ): List<ArtifactDraftEntity> {
        return try {
            val drafts = draftDao.get().getAllDraftsByUserId(userId)
            drafts.forEachIndexed { index, draft ->
                onProgress(ExportProgress.Drafts(index + 1, drafts.size))
                
                val safeTitle = sanitizeFilename(draft.title).ifBlank { "Untitled" }
                val dirName = "Drafts/draft_${draft.id.take(8)}_${safeTitle}"
                
                val metadata = DraftExportMetadata(
                    id = draft.id,
                    title = draft.title,
                    description = draft.description,
                    emotion = draft.emotion?.label,
                    lifecycle = draft.lifecycle.name,
                    createdAt = java.util.Date(draft.createdAt).toString(),
                    updatedAt = java.util.Date(draft.updatedAt).toString(),
                    durationMs = draft.durationMs
                )
                zipOut.addJsonEntry("$dirName/metadata.json", metadata)
                
                val audioFile = File(draft.localAudioPath)
                if (audioFile.exists()) {
                    zipOut.addFileEntry("$dirName/audio.${draft.mimeType.substringAfter("/")}", audioFile, decrypt = draft.isEncrypted)
                } else {
                    errors.add("Audio missing for draft: ${draft.id}")
                }
            }
            drafts
        } catch (e: Exception) {
            errors.add("Failed to export drafts: ${e.message}")
            emptyList()
        }
    }

    private suspend fun exportAuthoredComments(userId: String, zipOut: ZipOutputStream, errors: MutableList<String>): Int {
        return try {
            val snapshot = firestore.collectionGroup("comments")
                .whereEqualTo("creatorId", userId)
                .get().await()
            
            val comments = snapshot.documents.mapNotNull { doc ->
                doc.toObject(CommentDto::class.java)?.copy(id = doc.id)?.let { dto ->
                    CommentExport(
                        id = dto.id,
                        artifactId = dto.artifactId,
                        authorName = dto.author.name,
                        text = dto.text,
                        createdAt = dto.createdAt?.toDate()?.toString() ?: "Unknown",
                        status = dto.status
                    )
                }
            }
            if (comments.isNotEmpty()) {
                zipOut.addJsonEntry("Participation/comments_authored.json", comments)
            }
            comments.size
        } catch (e: Exception) {
            errors.add("Failed to export authored comments: ${e.message}")
            0
        }
    }

    private suspend fun exportReceivedComments(userId: String, artifacts: List<Artifact>, zipOut: ZipOutputStream, errors: MutableList<String>): Int {
        var total = 0
        try {
            val allReceived = mutableListOf<CommentExport>()
            artifacts.forEach { artifact ->
                val snapshot = firestore.collection("artifacts").document(artifact.id)
                    .collection("comments").get().await()
                
                val comments = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(CommentDto::class.java)?.copy(id = doc.id)?.let { dto ->
                        // Only include if not authored by the user themselves (to avoid duplicates)
                        if (dto.creatorId != userId) {
                            CommentExport(
                                id = dto.id,
                                artifactId = dto.artifactId,
                                authorName = dto.author.name,
                                text = dto.text,
                                createdAt = dto.createdAt?.toDate()?.toString() ?: "Unknown",
                                status = dto.status
                            )
                        } else null
                    }
                }
                allReceived.addAll(comments)
                total += comments.size
            }
            if (allReceived.isNotEmpty()) {
                zipOut.addJsonEntry("Participation/comments_received.json", allReceived)
            }
        } catch (e: Exception) {
            errors.add("Failed to export received comments: ${e.message}")
        }
        return total
    }

    private suspend fun exportResonance(userId: String, zipOut: ZipOutputStream, errors: MutableList<String>) {
        try {
            val following = userRepository.get().observeResonatingWithIds(userId).first()
            zipOut.addJsonEntry("Resonance/following.json", mapOf("followingIds" to following))
            
            // Followers (resonance_in)
            val snapshot = firestore.collection("users").document(userId)
                .collection("resonance_in").get().await()
            val followers = snapshot.documents.map { it.id }
            zipOut.addJsonEntry("Resonance/followers.json", mapOf("followerIds" to followers))
        } catch (e: Exception) {
            errors.add("Failed to export resonance data: ${e.message}")
        }
    }

    private suspend fun exportSavedArtifacts(userId: String, zipOut: ZipOutputStream, errors: MutableList<String>) {
        try {
            val savedIds = libraryRepository.get().getSavedArtifactIds(userId).first()
            zipOut.addJsonEntry("Saved/stayed_with_me.json", mapOf("savedArtifactIds" to savedIds))
        } catch (e: Exception) {
            errors.add("Failed to export saved artifacts: ${e.message}")
        }
    }

    private suspend fun exportEngagement(userId: String, zipOut: ZipOutputStream, errors: MutableList<String>): Int {
        return try {
            val snapshot = firestore.collection("users").document(userId)
                .collection("engagement").get().await()
            
            val engagement = snapshot.documents.mapNotNull { doc ->
                EngagementExport(
                    artifactId = doc.getString("artifactId") ?: doc.id,
                    isCommentUnlocked = doc.getBoolean("isCommentUnlocked") ?: false,
                    unlockTimestamp = doc.getTimestamp("unlockTimestamp")?.toDate()?.toString(),
                    engagementState = doc.getString("engagementState") ?: "LOCKED"
                )
            }
            if (engagement.isNotEmpty()) {
                zipOut.addJsonEntry("Participation/engagement_history.json", engagement)
            }
            engagement.size
        } catch (e: Exception) {
            errors.add("Failed to export engagement history: ${e.message}")
            0
        }
    }

    private suspend fun exportAuthoredReports(userId: String, zipOut: ZipOutputStream, errors: MutableList<String>): Int {
        return try {
            val snapshot = firestore.collection("reports")
                .whereEqualTo("reporterId", userId)
                .get().await()
            
            val reports = snapshot.documents.mapNotNull { doc ->
                ReportExport(
                    artifactId = doc.getString("artifactId") ?: "",
                    reason = doc.getString("reason") ?: "OTHER",
                    optionalDescription = doc.getString("optionalDescription") ?: "",
                    createdAt = doc.getTimestamp("createdAt")?.toDate()?.toString() ?: "Unknown",
                    status = doc.getString("status") ?: "PENDING"
                )
            }
            if (reports.isNotEmpty()) {
                zipOut.addJsonEntry("Safety/reports_authored.json", reports)
            }
            reports.size
        } catch (e: Exception) {
            errors.add("Failed to export authored reports: ${e.message}")
            0
        }
    }

    // Helper functions

    private fun ZipOutputStream.addTextEntry(name: String, text: String) {
        val entry = ZipEntry(name)
        putNextEntry(entry)
        write(text.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.addJsonEntry(name: String, data: Any) {
        val entry = ZipEntry(name)
        putNextEntry(entry)
        val encoded = when(data) {
            is UserIdentityExport -> json.encodeToString(data)
            is ArtifactExportMetadata -> json.encodeToString(data)
            is List<*> -> {
                val first = data.firstOrNull()
                when (first) {
                    is ArtifactExportMetadata -> json.encodeToString(data.filterIsInstance<ArtifactExportMetadata>())
                    is CommentExport -> json.encodeToString(data.filterIsInstance<CommentExport>())
                    is EngagementExport -> json.encodeToString(data.filterIsInstance<EngagementExport>())
                    is ReportExport -> json.encodeToString(data.filterIsInstance<ReportExport>())
                    else -> json.encodeToString(data.toString())
                }
            }
            is DraftExportMetadata -> json.encodeToString(data)
            is ExportManifest -> json.encodeToString(data)
            else -> json.encodeToString(data.toString())
        }
        write(encoded.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.addFileEntry(name: String, file: File, decrypt: Boolean = false) {
        if (!file.exists()) return
        val entry = ZipEntry(name)
        putNextEntry(entry)
        try {
            if (decrypt) {
                encryptedStorageManager.getEncryptedInputStream(file).use { input ->
                    input.copyTo(this)
                }
            } else {
                file.inputStream().use { input ->
                    input.copyTo(this)
                }
            }
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.STORAGE, "EXPORT_FILE_FAILED", mapOf("name" to name), e)
        }
        closeEntry()
    }

    private suspend fun ZipOutputStream.addRemoteAudioEntry(name: String, url: String, errors: MutableList<String>) {
        if (url.isBlank()) return
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("export_dl", ".m4a", context.cacheDir)
        }
        try {
            storage.getReferenceFromUrl(url).getFile(tempFile).await()
            addFileEntry(name, tempFile, decrypt = false)
        } catch (e: Exception) {
            errors.add("Failed to download audio from $url: ${e.message}")
        } finally {
            withContext(Dispatchers.IO) {
                tempFile.delete()
            }
        }
    }

    private fun sanitizeFilename(name: String?): String {
        if (name == null) return ""
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
    }

    private fun formatDate(timestamp: com.google.firebase.Timestamp): String {
        val date = timestamp.toDate()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(date)
    }

    // Export DTOs

    @Serializable
    private data class ArtifactExportMetadata(
        val id: String,
        val title: String,
        val description: String,
        val createdAt: String,
        val emotion: String,
        val emotionTag: String,
        val durationMs: Long,
        val visibility: String,
        val playCount: Long,
        val reactionCount: Long,
        val commentCount: Long,
        val transcript: List<TranscriptSegment>
    )

    @Serializable
    private data class DraftExportMetadata(
        val id: String,
        val title: String?,
        val description: String?,
        val emotion: String?,
        val lifecycle: String,
        val createdAt: String,
        val updatedAt: String,
        val durationMs: Long
    )

    @Serializable
    private data class UserIdentityExport(
        val anonymousId: String,
        val anonymousName: String,
        val anonymousSigil: String,
        val sigilConfig: SigilConfig,
        val bio: String,
        val resonanceInCount: Long,
        val resonanceOutCount: Long,
        val artifactsCount: Long
    )

    @Serializable
    private data class CommentExport(
        val id: String,
        val artifactId: String,
        val authorName: String,
        val text: String,
        val createdAt: String,
        val status: String
    )

    @Serializable
    private data class EngagementExport(
        val artifactId: String,
        val isCommentUnlocked: Boolean,
        val unlockTimestamp: String?,
        val engagementState: String
    )

    @Serializable
    private data class ReportExport(
        val artifactId: String,
        val reason: String,
        val optionalDescription: String,
        val createdAt: String,
        val status: String
    )

    @Serializable
    private data class ExportManifest(
        val version: String = "1.0",
        val exportedAt: String,
        val artifactCount: Int,
        val draftCount: Int,
        val commentAuthoredCount: Int,
        val commentReceivedCount: Int,
        val engagementCount: Int = 0,
        val reportsAuthoredCount: Int = 0,
        val errors: List<String> = emptyList()
    )
}
