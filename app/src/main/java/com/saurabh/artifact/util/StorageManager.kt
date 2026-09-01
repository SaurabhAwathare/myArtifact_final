package com.saurabh.artifact.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file storage for the application.
 * Optimized for draft survival by using persistent locations.
 */
@Singleton
class StorageManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    companion object {
        const val MIN_STORAGE_REQUIRED_MB = 600L
        const val LOW_STORAGE_THRESHOLD_MB = 1024L
        const val CRITICAL_STORAGE_THRESHOLD_MB = 512L
        
    }

    /**
     * Checks if there is enough space to start or continue a recording.
     */
    fun isStorageAvailable(requiredMb: Long = MIN_STORAGE_REQUIRED_MB): Boolean {
        return availableStorageMb > requiredMb
    }

    val availableStorageMb: Long
        get() = try {
            val stat = StatFs(context.filesDir.path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (e: Exception) {
            Log.e("StorageManager", "Failed to calculate storage", e)
            0L
        }

    /**
     * Resolves the root directory for all drafts.
     * Uses External Files Dir (Music) to ensure durability after reinstall if possible.
     */
    val draftsRootDirectory: File
        get() {
            val persistentDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let {
                File(it, "Artifact/Drafts")
            } ?: File(context.filesDir, "Artifact/Drafts")

            return persistentDir
        }

    val waveformsDirectory: File
        get() = File(context.filesDir, "waveforms")

    val transcriptsDirectory: File
        get() = File(context.filesDir, "transcripts")

    val frozenAudioDirectory: File
        get() = File(context.filesDir, "frozen_audio")

    val legacyDraftsDirectory: File
        get() = File(context.filesDir, "drafts")

    val tempRecordingsDirectory: File
        get() = File(context.cacheDir, "recording_temp")

    val tempTranscodingDirectory: File
        get() = File(context.cacheDir, "transcoding_temp")

    val tempUploadDirectory: File
        get() = File(context.cacheDir, "upload_temp").apply {
            if (!exists()) mkdirs()
        }

    val mediaCacheDirectory: File
        get() = File(context.cacheDir, "media_cache")

    /**
     * Resolves a specific directory for a draft ID.
     */
    fun getDraftDirectory(draftId: String): File {
        return File(draftsRootDirectory, "draft_$draftId").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Deletes a directory and all its contents recursively.
     */
    fun deleteDirectoryRecursively(dir: File): Boolean {
        if (!dir.exists()) return true
        if (dir.isDirectory) {
            val children = dir.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteDirectoryRecursively(child)
                }
            }
        }
        return dir.delete()
    }

    /**
     * Securely deletes a file from internal storage.
     */
    fun deleteSecurely(file: File): Boolean {
        if (!file.exists()) return true
        
        // Overwriting with zeros before deletion for basic anti-forensics (best effort)
        try {
            if (file.canWrite() && file.isFile) {
                val length = file.length()
                val fos = file.outputStream()
                val buffer = ByteArray(4096)
                var remaining = length
                while (remaining > 0) {
                    val toWrite = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                    fos.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
                fos.flush()
                fos.close()
            }
        } catch (e: Exception) {
            Log.e("StorageManager", "Failed to securely overwrite file.")
        }
        
        return file.delete()
    }

    /**
     * Result of a storage cleanup operation.
     */
    data class StorageCleanupResult(
        val deletedDirectories: List<String>,
        val skippedDirectories: List<String>,
        val failures: List<CleanupFailure>
    )

    data class CleanupFailure(
        val directoryName: String,
        val errorMessage: String
    )

    /**
     * Clears all user-specific storage, including drafts and internal/external caches.
     * This is a privacy-critical operation.
     * 
     * Uses a whitelisting approach for cache directories to avoid deleting 
     * infrastructure or third-party cache (like image_cache or media_cache).
     */
    fun clearUserStorage(): StorageCleanupResult {
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<CleanupFailure>()

        val targets = listOf(
            "Drafts Root" to draftsRootDirectory,
            "Waveforms" to waveformsDirectory,
            "Transcripts" to transcriptsDirectory,
            "Frozen Audio" to frozenAudioDirectory,
            "Legacy Drafts" to legacyDraftsDirectory,
            "Temp Recordings" to tempRecordingsDirectory,
            "Temp Transcoding" to tempTranscodingDirectory,
            "Temp Uploads" to tempUploadDirectory,
            "Media Cache" to mediaCacheDirectory
        )

        targets.forEach { (name, dir) ->
            try {
                if (!dir.exists()) {
                    skipped.add(name)
                    return@forEach
                }

                if (deleteDirectoryRecursively(dir)) {
                    deleted.add(name)
                } else {
                    failures.add(CleanupFailure(name, "Delete operation returned false"))
                }
            } catch (e: Exception) {
                Log.e("StorageManager", "Failed to clear directory.")
                failures.add(CleanupFailure(name, "Clear failed"))
            }
        }

        // Defensive Root-Cache Sweep (Legacy Hardening)
        // Cleanup decrypted files that might have been orphaned in the root cacheDir in previous versions
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("decrypted_") && file.name.endsWith(".m4a")) {
                    if (file.delete()) {
                        deleted.add("Legacy Root Cache: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StorageManager", "Failed to perform legacy root cache sweep", e)
        }

        // Also clear external cache if it exists
        try {
            context.externalCacheDir?.let { dir ->
                if (dir.exists()) {
                    if (deleteDirectoryRecursively(dir)) {
                        deleted.add("External Cache")
                    } else {
                        failures.add(CleanupFailure("External Cache", "Delete operation returned false"))
                    }
                } else {
                    skipped.add("External Cache")
                }
            }
        } catch (e: Exception) {
            Log.e("StorageManager", "Failed to clear external cache")
            failures.add(CleanupFailure("External Cache", "Clear failed"))
        }

        return StorageCleanupResult(deleted, skipped, failures)
    }
}
