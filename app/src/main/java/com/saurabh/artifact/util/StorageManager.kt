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
        private const val MIN_STORAGE_REQUIRED_MB = 100L
    }

    /**
     * Checks if there is enough space to start or continue a recording.
     */
    fun isStorageAvailable(requiredMb: Long = MIN_STORAGE_REQUIRED_MB): Boolean {
        return getAvailableStorageMb() > requiredMb
    }

    /**
     * Returns available storage on the device in Megabytes.
     */
    fun getAvailableStorageMb(): Long {
        return try {
            val stats = StatFs(context.filesDir.absolutePath)
            val availableBlocks = stats.availableBlocksLong
            val blockSize = stats.blockSizeLong
            (availableBlocks * blockSize) / (1024 * 1024)
        } catch (e: Exception) {
            Log.e("StorageManager", "Failed to calculate storage", e)
            0L
        }
    }


    /**
     * Resolves the root directory for all drafts.
     * Uses External Files Dir (Music) to ensure durability after reinstall if possible.
     */
    fun getDraftsRootDirectory(): File {
        val persistentDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { 
            File(it, "Artifact/Drafts") 
        } ?: File(context.filesDir, "Artifact/Drafts")
        
        return persistentDir.apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Resolves a specific directory for a draft ID.
     */
    fun getDraftDirectory(draftId: String): File {
        return File(getDraftsRootDirectory(), "draft_$draftId").apply {
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
            Log.e("StorageManager", "Failed to securely overwrite file: ${file.path}", e)
        }
        
        return file.delete()
    }
}
