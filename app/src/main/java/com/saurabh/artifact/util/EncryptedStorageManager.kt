package com.saurabh.artifact.util

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedStorageManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    fun getEncryptedOutputStream(file: File): OutputStream {
        val encryptedFile = EncryptedFile.Builder(
            file,
            context,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        return encryptedFile.openFileOutput()
    }

    fun getEncryptedInputStream(file: File): InputStream {
        val encryptedFile = EncryptedFile.Builder(
            file,
            context,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        return encryptedFile.openFileInput()
    }

    fun deleteSecurely(file: File) {
        if (!file.exists()) return
        
        // Zero-fill before deletion for extra security
        try {
            val length = file.length()
            val out = file.outputStream()
            val zeros = ByteArray(8192)
            var written = 0L
            while (written < length) {
                val toWrite = minOf(zeros.size.toLong(), length - written).toInt()
                out.write(zeros, 0, toWrite)
                written += toWrite
            }
            out.flush()
            out.close()
        } catch (e: Exception) {
            // Fallback to normal delete if zero-fill fails
        }
        file.delete()
    }
}
