package com.saurabh.artifact.audio

import android.content.Context
import android.util.Log
import com.saurabh.artifact.security.SecurityArchitecture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDraftManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val draftDir by lazy {
        File(context.filesDir, "draft_audio").apply {
            if (!exists()) mkdirs()
        }
    }

    private val encryptedDir by lazy {
        File(context.filesDir, "encrypted_drafts").apply {
            if (!exists()) mkdirs()
        }
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

    fun createDraftFile(extension: String = "m4a"): File {
        return File(draftDir, "draft_${System.currentTimeMillis()}.$extension")
    }

    fun createEncryptedDraftFile(draftId: String, extension: String = "m4a"): File {
        return File(encryptedDir, "enc_${draftId}.$extension")
    }

    fun getEncryptedOutputStream(file: File): OutputStream {
        return SecurityArchitecture.getEncryptedFile(context, file).openFileOutput()
    }

    fun getEncryptedInputStream(file: File): InputStream {
        return SecurityArchitecture.getEncryptedFile(context, file).openFileInput()
    }

    fun createWaveformFile(draftId: String): File {
        return File(waveformDir, "waveform_$draftId.json")
    }

    fun createTranscriptFile(draftId: String): File {
        return File(transcriptDir, "transcript_$draftId.txt")
    }

    fun deleteDraft(path: String): Boolean {
        val file = File(path)
        if (file.absolutePath.contains("encrypted_drafts")) {
            SecurityArchitecture.secureDelete(file)
            return true
        }
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

    fun cleanupOrphans(knownPaths: Set<String>) {
        // Legacy cleanup for old "drafts" directory
        val legacyDir = File(context.filesDir, "drafts")
        if (legacyDir.exists() && legacyDir.isDirectory) {
            legacyDir.listFiles()?.forEach { it.delete() }
            legacyDir.delete()
        }

        listOf(draftDir, encryptedDir, waveformDir, transcriptDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.absolutePath !in knownPaths) {
                    if (dir == encryptedDir) {
                        SecurityArchitecture.secureDelete(file)
                    } else {
                        file.delete()
                    }
                }
            }
        }
    }
}
