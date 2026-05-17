package com.saurabh.artifact.util

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages secure file storage using Android Security Crypto library.
 * Provides EncryptedFile instances for audio and transcript storage.
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    companion object {
        private const val MIN_STORAGE_REQUIRED_MB = 100L
        private const val CRITICAL_STORAGE_THRESHOLD_MB = 20L
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
     * Creates an EncryptedFile for writing.
     */
    fun getEncryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            file,
            context,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * Writes data to an encrypted file.
     */
    fun writeEncrypted(file: File, data: ByteArray) {
        val encryptedFile = getEncryptedFile(file)
        val outputStream: OutputStream = encryptedFile.openFileOutput()
        outputStream.write(data)
        outputStream.flush()
        outputStream.close()
    }

    /**
     * Reads data from an encrypted file.
     */
    fun readEncrypted(file: File): ByteArray {
        val encryptedFile = getEncryptedFile(file)
        val inputStream: InputStream = encryptedFile.openFileInput()
        val data = inputStream.readBytes()
        inputStream.close()
        return data
    }

    /**
     * Securely deletes a file from internal storage.
     */
    fun deleteSecurely(file: File): Boolean {
        if (!file.exists()) return true
        
        // Overwriting with zeros before deletion for basic anti-forensics (best effort)
        try {
            if (file.canWrite()) {
                val length = file.length()
                val fos = file.outputStream()
                fos.write(ByteArray(length.toInt().coerceAtMost(1024 * 1024)))
                fos.flush()
                fos.close()
            }
        } catch (e: Exception) {
            // Ignore errors during overwrite
        }
        
        return file.delete()
    }
}
