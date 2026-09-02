package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.model.DraftManifest
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.util.EncryptedStorageManager
import com.saurabh.artifact.util.StorageManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDraftManager @Inject constructor(
    private val storageManager: StorageManager,
    private val encryptedStorageManager: EncryptedStorageManager,
) {
    fun createDraftFile(draftId: String, extension: String = "m4a"): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, "audio.$extension")
    }

    fun createTranscriptFile(draftId: String): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, "transcript.txt")
    }

    private fun getManifestFile(draftId: String): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, ".metadata")
    }

    /**
     * Writes an encrypted ownership manifest to the draft directory.
     * This serves as a recovery hint if the primary database is lost.
     */
    fun writeManifest(
        draftId: String, 
        userId: String, 
        createdAt: Long, 
        mimeType: String,
        title: String? = null,
        emotion: Emotion? = null
    ) {
        try {
            val manifest = DraftManifest(draftId, userId, createdAt, mimeType, title, emotion)
            val file = getManifestFile(draftId)
            encryptedStorageManager.getEncryptedOutputStream(file).use { output ->
                output.write(Json.encodeToString(manifest).toByteArray())
            }
        } catch (e: Exception) {
            Log.e("LocalDraftManager", "Failed to write draft manifest for $draftId", e)
        }
    }

    /**
     * Attempts to read and decrypt the ownership manifest for a given draft.
     */
    fun readManifest(draftId: String): DraftManifest? {
        val file = getManifestFile(draftId)
        if (!file.exists()) return null
        
        return try {
            encryptedStorageManager.getEncryptedInputStream(file).use { input ->
                Json.decodeFromString<DraftManifest>(input.readBytes().decodeToString())
            }
        } catch (e: Exception) {
            Log.w("LocalDraftManager", "Failed to read manifest for $draftId (likely ownership mismatch or corruption)")
            null
        }
    }

    /**
     * Scans the filesystem for draft directories that are not currently indexed in the database.
     * @param knownIds Set of IDs already present in the database.
     */
    fun findOrphanedDraftDirectories(knownIds: Set<String>): List<String> {
        val rootDir = storageManager.draftsRootDirectory
        val folders = rootDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("draft_") } ?: emptyList()
        
        return folders.mapNotNull { folder ->
            val draftId = folder.name.substringAfter("draft_")
            if (draftId !in knownIds) draftId else null
        }
    }

    /**
     * Reconciles the filesystem with the database to remove orphaned files and directories.
     * @param allDrafts List of all valid drafts currently in the database.
     * @param gracePeriodMs Files created within this duration will not be deleted (default 2 hours).
     */
    fun reconcileStorage(
        allDrafts: List<com.saurabh.artifact.data.local.ArtifactDraftEntity>,
        gracePeriodMs: Long = RetentionPolicy.RECONCILIATION_GRACE_PERIOD_MS
    ) {
        val now = System.currentTimeMillis()
        val validDraftIds = allDrafts.asSequence().map { it.id }.toSet()
        val knownPaths = mutableSetOf<String>()

        val rootDir = storageManager.draftsRootDirectory
        val existingDraftFolders = rootDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("draft_") } ?: emptyList()

        // SANITY GATE: If DB returns zero drafts but folders exist, abort destructive purge.
        // This protects against temporary Room query failures or initialization races.
        if (allDrafts.isEmpty() && existingDraftFolders.isNotEmpty()) {
            Log.w("LocalDraftManager", "Reconciliation aborted: DB is empty but ${existingDraftFolders.size} folders found. Possible Room failure.")
            return
        }

        allDrafts.forEach { draft ->
            knownPaths.add(File(draft.localAudioPath).absolutePath)
            draft.rawPcmPath?.let { knownPaths.add(File(it).absolutePath) }
            draft.waveformPath?.let { knownPaths.add(File(it).absolutePath) }
            draft.frozenAudioPath?.let { knownPaths.add(File(it).absolutePath) }
        }

        // 1. Clean legacy directories
        val legacyDir = storageManager.legacyDraftsDirectory
        if (legacyDir.exists() && legacyDir.isDirectory) {
            Log.i("LocalDraftManager", "Removing legacy drafts directory")
            storageManager.deleteDirectoryRecursively(legacyDir)
        }

        // 2. Scan and prune the root drafts directory (directory level)
        existingDraftFolders.forEach { draftDir ->
            val draftId = draftDir.name.substringAfter("draft_")
            if (draftId !in validDraftIds) {
                // Check grace period: if the directory was created recently, skip it
                if ((now - draftDir.lastModified()) > gracePeriodMs) {
                    Log.i("LocalDraftManager", "Deleting orphaned draft directory.")
                    storageManager.deleteDirectoryRecursively(draftDir)
                }
            } else {
                // 3. Within a valid draft directory, prune untracked files
                draftDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.absolutePath !in knownPaths) {
                        if ((now - file.lastModified()) > gracePeriodMs) {
                            Log.i("LocalDraftManager", "Deleting untracked file in valid draft.")
                            file.delete()
                        }
                    }
                }
            }
        }

        // 4. Prune specific sidecar directories
        listOf(
            storageManager.waveformsDirectory,
            storageManager.transcriptsDirectory,
            storageManager.frozenAudioDirectory
        ).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.absolutePath !in knownPaths && (now - file.lastModified() > gracePeriodMs)) {
                        Log.i("LocalDraftManager", "Deleting untracked sidecar file")
                        file.delete()
                    }
                }
            }
        }
    }
}
