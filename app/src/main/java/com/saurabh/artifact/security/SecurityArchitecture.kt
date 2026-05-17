package com.saurabh.artifact.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import java.io.File
import java.security.SecureRandom

object SecurityArchitecture {

    /**
     * Creates an EncryptedFile instance for secure local storage of audio drafts.
     */
    fun getEncryptedFile(context: Context, file: File): EncryptedFile {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        
        return EncryptedFile.Builder(
            file,
            context,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * Securely deletes a file by overwriting it with random data before deletion.
     * This is critical for privacy-first applications.
     */
    fun secureDelete(file: File) {
        if (file.exists() && file.isFile) {
            val length = file.length()
            val secureRandom = SecureRandom()
            val randomData = ByteArray(1024)
            
            file.outputStream().use { fos ->
                var written = 0L
                while (written < length) {
                    secureRandom.nextBytes(randomData)
                    val toWrite = minOf(randomData.size.toLong(), length - written).toInt()
                    fos.write(randomData, 0, toWrite)
                    written += toWrite
                }
                fos.flush()
            }
        }
        file.delete()
    }

    /**
     * Generates a temporary secure file path for editing operations.
     */
    fun createSecureTempFile(context: Context, suffix: String = ".mp3"): File {
        val tempDir = File(context.cacheDir, "secure_edits")
        if (!tempDir.exists()) tempDir.mkdirs()
        return File(tempDir, "tmp_${System.currentTimeMillis()}_${SecureRandom().nextInt(1000)}$suffix")
    }
}
