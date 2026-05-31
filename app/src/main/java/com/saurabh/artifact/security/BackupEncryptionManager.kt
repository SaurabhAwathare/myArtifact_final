package com.saurabh.artifact.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cash.z.ecc.android.bip39.Mnemonics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupPrefs by preferencesDataStore(name = "backup_security")

@Singleton
class BackupEncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mnemonicKey = stringPreferencesKey("recovery_mnemonic")
    private val backupSaltKey = stringPreferencesKey("backup_salt")

    /**
     * Generates a new BIP39 mnemonic and stores it locally.
     * This is only called once during onboarding or when backup is enabled.
     */
    suspend fun generateRecoveryPhrase(): String {
        val words = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_12).words
        val phrase = words.joinToString(" ") { it.concatToString() }
        context.backupPrefs.edit { it[mnemonicKey] = phrase }
        return phrase
    }

    /**
     * Retrieves the stored recovery phrase.
     */
    fun getRecoveryPhrase(): Flow<String?> = context.backupPrefs.data.map { it[mnemonicKey] }

    /**
     * Derives a 256-bit encryption key from the mnemonic using Argon2id or PBKDF2.
     * For MVP, we'll use a high-iteration PBKDF2 as a foundation.
     */
    suspend fun getBackupKey(): SecretKeySpec {
        val phrase = getRecoveryPhrase().first() ?: throw IllegalStateException("Backup not initialized")
        val salt = getOrCreateSalt()
        
        // Use the SecurityArchitecture's existing robust derivation
        val keyBytes = SecurityArchitecture.deriveBackupKey(phrase, salt)
        return SecretKeySpec(keyBytes, "AES")
    }

    private suspend fun getOrCreateSalt(): ByteArray {
        val storedSalt = context.backupPrefs.data.first()[backupSaltKey]
        return if (storedSalt != null) {
            android.util.Base64.decode(storedSalt, android.util.Base64.DEFAULT)
        } else {
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            context.backupPrefs.edit { it[backupSaltKey] = android.util.Base64.encodeToString(salt, android.util.Base64.DEFAULT) }
            salt
        }
    }

    /**
     * Encrypts data for cloud backup.
     */
    suspend fun encryptForBackup(data: ByteArray): ByteArray {
        val key = getBackupKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        
        val ciphertext = cipher.doFinal(data)
        return iv + ciphertext // Prepend IV for storage
    }

    /**
     * Decrypts data from cloud backup.
     */
    suspend fun decryptFromBackup(encryptedData: ByteArray): ByteArray {
        val key = getBackupKey()
        val iv = encryptedData.sliceArray(0 until 12)
        val ciphertext = encryptedData.sliceArray(12 until encryptedData.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
