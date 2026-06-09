package com.saurabh.artifact.security

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.subtle.AesGcmJce
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupPrefs by preferencesDataStore(name = "backup_security")

@Singleton
class BackupEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val mnemonicKey = stringPreferencesKey("recovery_mnemonic")
    
    // Master key for local storage protection
    private val localAead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "backup_local_keyset", "backup_local_key_preference")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://backup_local_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /**
     * Stores the mnemonic securely after encrypting it with a Keystore-backed key.
     */
    suspend fun saveMnemonic(mnemonic: String) {
        val encrypted = localAead.encrypt(mnemonic.toByteArray(), null)
        val encoded = Base64.encodeToString(encrypted, Base64.DEFAULT)
        context.backupPrefs.edit { it[mnemonicKey] = encoded }
    }

    /**
     * Retrieves and decrypts the stored recovery phrase.
     */
    suspend fun getRecoveryPhrase(): String? {
        val stored = context.backupPrefs.data.first()[mnemonicKey] ?: return null
        return try {
            val encrypted = Base64.decode(stored, Base64.DEFAULT)
            localAead.decrypt(encrypted, null).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Derives a 256-bit encryption key from the mnemonic.
     * Uses a deterministic salt to ensure cross-device recovery.
     */
    suspend fun getBackupKey(): SecretKeySpec {
        val phrase = getRecoveryPhrase() ?: throw IllegalStateException("Backup not initialized")
        
        // Use a fixed deterministic salt for cross-device recovery from mnemonic.
        // This is safe because the entropy comes from the 128/256-bit mnemonic seed.
        val deterministicSalt = "artifact_backup_v1_salt".toByteArray()
        
        val keyBytes = SecurityArchitecture.deriveBackupKey(phrase, deterministicSalt)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts data for cloud backup using Tink's AesGcmJce.
     */
    suspend fun encryptForBackup(data: ByteArray): ByteArray {
        val key = getBackupKey()
        val primitive = AesGcmJce(key.encoded)
        // AesGcmJce automatically handles IV generation and prepending
        return primitive.encrypt(data, null)
    }

    /**
     * Decrypts data from cloud backup.
     */
    suspend fun decryptFromBackup(encryptedData: ByteArray): ByteArray {
        val key = getBackupKey()
        val primitive = AesGcmJce(key.encoded)
        return primitive.decrypt(encryptedData, null)
    }
}
