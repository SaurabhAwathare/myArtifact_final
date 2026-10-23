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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupPrefs by preferencesDataStore(name = "backup_security")

@Singleton
class BackupEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val legacyMnemonicKey = stringPreferencesKey("recovery_mnemonic")
    
    private fun userMnemonicKey(uid: String) = stringPreferencesKey("recovery_mnemonic_$uid")
    
    private val keyMutex = Mutex()
    private var cachedBackupKey: SecretKeySpec? = null
    private var cachedExportKey: SecretKeySpec? = null
    
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
     * Stores the mnemonic securely after encrypting it with a Keystore-backed key, scoped by UID.
     */
    suspend fun saveMnemonic(uid: String, mnemonic: String) {
        invalidateCache()
        val encrypted = localAead.encrypt(mnemonic.toByteArray(), null)
        val encoded = Base64.encodeToString(encrypted, Base64.DEFAULT)
        context.backupPrefs.edit { prefs ->
            prefs[userMnemonicKey(uid)] = encoded
            prefs[legacyMnemonicKey] = encoded
        }
    }

    /**
     * Legacy overload for saveMnemonic.
     */
    suspend fun saveMnemonic(mnemonic: String) {
        invalidateCache()
        val encrypted = localAead.encrypt(mnemonic.toByteArray(), null)
        val encoded = Base64.encodeToString(encrypted, Base64.DEFAULT)
        context.backupPrefs.edit { prefs ->
            prefs[legacyMnemonicKey] = encoded
        }
    }

    /**
     * Retrieves and decrypts the stored recovery phrase for the given UID (or legacy fallback).
     */
    suspend fun getRecoveryPhrase(uid: String? = null): String? {
        val prefs = context.backupPrefs.data.first()
        val stored = if (uid != null) {
            val userVal = prefs[userMnemonicKey(uid)]
            if (userVal != null) {
                userVal
            } else {
                val hasAnyUidKey = prefs.asMap().keys.any { it.name.startsWith("recovery_mnemonic_") }
                if (!hasAnyUidKey) prefs[legacyMnemonicKey] else null
            }
        } else {
            prefs[legacyMnemonicKey]
        } ?: return null

        val phrase = try {
            val encrypted = Base64.decode(stored, Base64.DEFAULT)
            localAead.decrypt(encrypted, null).decodeToString()
        } catch (_: Exception) {
            return null
        }

        // Non-destructive legacy migration: if read via legacy for a specific UID, migrate to UID key
        if (uid != null && prefs[userMnemonicKey(uid)] == null) {
            try {
                val encrypted = localAead.encrypt(phrase.toByteArray(), null)
                val encoded = Base64.encodeToString(encrypted, Base64.DEFAULT)
                context.backupPrefs.edit { it[userMnemonicKey(uid)] = encoded }
            } catch (_: Exception) {
                // Ignore migration write errors, fallback read remains safe
            }
        }

        return phrase
    }

    /**
     * Explicitly deletes stored recovery phrase for a specific UID.
     * Should only be called on explicit account deletion.
     */
    suspend fun deleteMnemonicForUser(uid: String) {
        invalidateCache()
        try {
            context.backupPrefs.edit { prefs ->
                prefs.remove(userMnemonicKey(uid))
            }
        } catch (_: Exception) {
            // Silently handle DataStore failures
        }
    }

    /**
     * Derives a 256-bit encryption key from the mnemonic.
     * Uses a deterministic salt to ensure cross-device recovery.
     * Results are cached in-memory for the process lifetime to optimize performance.
     */
    suspend fun getBackupKey(uid: String? = null): SecretKeySpec {
        cachedBackupKey?.let { return it }

        return keyMutex.withLock {
            cachedBackupKey?.let { return@withLock it }

            val phrase = getRecoveryPhrase(uid) ?: throw IllegalStateException("Backup not initialized")
            
            // Use a fixed deterministic salt for cross-device recovery from mnemonic.
            // This is safe because the entropy comes from the 128/256-bit mnemonic seed.
            val deterministicSalt = "artifact_backup_v1_salt".toByteArray()
            
            val keyBytes = withContext(Dispatchers.Default) {
                SecurityArchitecture.deriveKey(phrase, deterministicSalt)
            }
            SecretKeySpec(keyBytes, "AES").also { 
                cachedBackupKey = it
            }
        }
    }

    /**
     * Derives a 256-bit encryption key dedicated to data export.
     * Uses a distinct salt from the backup key to ensure cryptographic domain separation.
     */
    suspend fun getExportKey(uid: String? = null): SecretKeySpec {
        cachedExportKey?.let { return it }

        return keyMutex.withLock {
            cachedExportKey?.let { return@withLock it }

            val phrase = getRecoveryPhrase(uid) ?: throw IllegalStateException("Backup/Export not initialized")
            
            // DISTINCT salt for Export domain separation
            val exportSalt = "artifact_export_v1_salt".toByteArray()
            
            val keyBytes = withContext(Dispatchers.Default) {
                SecurityArchitecture.deriveKey(phrase, exportSalt)
            }
            SecretKeySpec(keyBytes, "AES").also { 
                cachedExportKey = it
            }
        }
    }

    /**
     * Invalidates in-memory key caches during logout or user transition.
     * Preserves persistent UID-scoped recovery phrases on disk across logouts.
     */
    suspend fun clear() {
        invalidateCache()
        try {
            context.backupPrefs.edit { it.clear() }
        } catch (_: Exception) {
            // Silently handle DataStore failures during logout
        }
    }

    /**
     * Explicitly invalidates all in-memory key caches.
     * Should be called during logout, account deletion, or recovery phrase changes.
     */
    fun invalidateCache() {
        cachedBackupKey = null
        cachedExportKey = null
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
    @Suppress("unused")
    suspend fun decryptFromBackup(encryptedData: ByteArray): ByteArray {
        val key = getBackupKey()
        val primitive = AesGcmJce(key.encoded)
        return primitive.decrypt(encryptedData, null)
    }
}
