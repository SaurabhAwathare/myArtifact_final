package com.saurabh.artifact.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import com.saurabh.artifact.util.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

    @Singleton
    class LocalDraftManager @Inject constructor(
        @ApplicationContext private val context: Context,
        private val storageManager: StorageManager,
    ) {
    private val draftDir by lazy {
        storageManager.getDraftsRootDirectory()
    }

    private val waveformDir by lazy {
        File(context.filesDir, "waveforms").apply {
            if (!exists()) mkdirs()
        }
    }

    private val transcriptDir by lazy {
        File(context.filesDir, "transcripts").apply {
            if (!exists()) mkdirs()
        }
    }

    fun createDraftFile(draftId: String, extension: String = "m4a"): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, "audio.$extension")
    }

    fun getOutputStream(file: File): OutputStream = file.outputStream()

    fun getInputStream(file: File): InputStream = file.inputStream()

    fun createWaveformFile(draftId: String): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, "waveform.json")
    }

    fun createTranscriptFile(draftId: String): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, "transcript.txt")
    }

    fun createTempFile(draftId: String, prefix: String, extension: String): File {
        val dir = storageManager.getDraftDirectory(draftId)
        return File(dir, "${prefix}_${System.currentTimeMillis()}.$extension")
    }

    fun deleteDraft(path: String): Boolean {
        val file = File(path)
        return file.delete()
    }

    fun draftExists(path: String): Boolean {
        return File(path).exists()
    }

    /**
     * Securely deletes all physical files associated with a draft.
     */
    fun deleteDraftFiles(draft: com.saurabh.artifact.data.local.ArtifactDraftEntity) {
        try {
            File(draft.localAudioPath).delete()
            draft.localTranscriptPath?.let { File(it).delete() }
            draft.waveformPath?.let { File(it).delete() }
            draft.frozenAudioPath?.let { File(it).delete() }
            Log.d("LocalDraftManager", "Deleted files for draft: ${draft.id}")
        } catch (e: Exception) {
            Log.e("LocalDraftManager", "Failed to delete files for draft: ${draft.id}", e)
        }
    }

    /**
     * Reconciles the filesystem with the database to remove orphaned files and directories.
     * @param allDrafts List of all valid drafts currently in the database.
     * @param gracePeriodMs Files created within this duration will not be deleted (default 2 hours).
     */
    fun reconcileStorage(
        allDrafts: List<com.saurabh.artifact.data.local.ArtifactDraftEntity>,
        gracePeriodMs: Long = 2 * 60 * 60 * 1000L
    ) {
        val now = System.currentTimeMillis()
        val validDraftIds = allDrafts.map { it.id }.toSet()
        val knownPaths = mutableSetOf<String>()
        
        allDrafts.forEach { draft ->
            knownPaths.add(File(draft.localAudioPath).absolutePath)
            draft.rawPcmPath?.let { knownPaths.add(File(it).absolutePath) }
            draft.waveformPath?.let { knownPaths.add(File(it).absolutePath) }
            draft.localTranscriptPath?.let { knownPaths.add(File(it).absolutePath) }
            draft.frozenAudioPath?.let { knownPaths.add(File(it).absolutePath) }
        }

        // 1. Clean legacy directories
        val legacyDir = File(context.filesDir, "drafts")
        if (legacyDir.exists() && legacyDir.isDirectory) {
            Log.i("LocalDraftManager", "Removing legacy drafts directory")
            storageManager.deleteDirectoryRecursively(legacyDir)
        }

        // 2. Scan and prune the root drafts directory (directory level)
        val rootDir = storageManager.getDraftsRootDirectory()
        rootDir.listFiles()?.forEach { draftDir ->
            if (draftDir.isDirectory && draftDir.name.startsWith("draft_")) {
                val draftId = draftDir.name.substringAfter("draft_")
                if (draftId !in validDraftIds) {
                    // Check grace period: if the directory was created recently, skip it
                    if ((now - draftDir.lastModified()) > gracePeriodMs) {
                        Log.i("LocalDraftManager", "Deleting orphaned draft directory: ${draftDir.name}")
                        storageManager.deleteDirectoryRecursively(draftDir)
                    }
                } else {
                    // 3. Within a valid draft directory, prune untracked files
                    draftDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.absolutePath !in knownPaths) {
                            if ((now - file.lastModified()) > gracePeriodMs) {
                                Log.i("LocalDraftManager", "Deleting untracked file in valid draft: ${file.path}")
                                file.delete()
                            }
                        }
                    }
                }
            }
        }

        // 4. Prune other specific tracked directories if they exist outside draft root (safety)
        listOf(waveformDir, transcriptDir).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.absolutePath !in knownPaths && (now - file.lastModified() > gracePeriodMs)) {
                        Log.i("LocalDraftManager", "Deleting untracked file in side-car directory: ${file.path}")
                        file.delete()
                    }
                }
            }
        }
    }

    @Deprecated("Use reconcileStorage instead", ReplaceWith("reconcileStorage(allDrafts)"))
    fun cleanupOrphans(knownPaths: Set<String>) {
        // Implementation kept for backward compatibility if needed temporarily
        listOf(draftDir, waveformDir, transcriptDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.absolutePath !in knownPaths) {
                    file.delete()
                }
            }
        }
    }

}
