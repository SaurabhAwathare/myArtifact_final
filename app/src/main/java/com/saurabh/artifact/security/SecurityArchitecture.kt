package com.saurabh.artifact.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityArchitecture {

    /**
     * Creates an EncryptedFile instance for secure local storage of audio drafts.
     * Prefers hardware-backed security (StrongBox or TEE).
     */
    fun getEncryptedFile(context: Context, file: File): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true) // Prefer StrongBox for hardware-level security
            .build()
        
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * Derives a high-entropy key from a user passphrase using PBKDF2WithHmacSHA256.
     * We use a high iteration count (600,000) to ensure brute-force resistance.
     * This is used for Tier 2 Secure Backup.
     */
    fun deriveBackupKey(passphrase: String, salt: ByteArray): ByteArray {
        val iterations = 600000
        val keyLength = 256
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyLength)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
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
