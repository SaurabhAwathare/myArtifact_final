package com.saurabh.artifact.security

import android.content.Context
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.subtle.AesGcmJce
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed class PreloadResult {
    data object Success : PreloadResult()
    data object RecoveryRequired : PreloadResult()
    data object InitialSetup : PreloadResult()
    data class FatalFailure(val throwable: Throwable) : PreloadResult()
}

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @Named("dbEncryptionDataStore") private val dataStore: DataStore<Preferences>,
    private val diagnosticLogger: DiagnosticLogger
) {
    private val googleAEAD: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "master_keyset", "master_key_preference")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://db_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    @Volatile
    private var cachedPassphrase: ByteArray? = null

    /**
     * Checks if a recovery wrapper exists in the local DataStore.
     */
    val isRecoverySetup: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[RECOVERY_WRAPPER_KEY] != null }

    /**
     * Preloads and validates the database passphrase on a background thread.
     * Must be called during the startup sequence before the database is accessed.
     */
    suspend fun preload(): PreloadResult = withContext(Dispatchers.IO) {
        if (cachedPassphrase != null) return@withContext PreloadResult.Success

        try {
            val prefs = dataStore.data.first()
            val encryptedPassphrase = prefs[DB_PASSPHRASE_KEY]
            val recoveryWrapper = prefs[RECOVERY_WRAPPER_KEY]

            if (encryptedPassphrase == null) {
                // If no passphrase but a recovery wrapper exists, this is a restore state
                if (recoveryWrapper != null) {
                    return@withContext PreloadResult.RecoveryRequired
                }
                
                // Pure new installation
                cachedPassphrase = generateAndStoreNewPassphrase()
                return@withContext PreloadResult.InitialSetup
            }

            try {
                val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
                val decrypted = googleAEAD.decrypt(encryptedBytes, null)
                
                // VALIDATION: Check if this passphrase can actually open the database
                if (!validatePassphrase(decrypted)) {
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_PASSPHRASE_VALIDATION_FAILED")
                    // If we have a recovery wrapper, offer recovery instead of wiping
                    if (recoveryWrapper != null) {
                        return@withContext PreloadResult.RecoveryRequired
                    }
                    cachedPassphrase = generateAndStoreNewPassphrase()
                } else {
                    cachedPassphrase = decrypted
                }
            } catch (e: Exception) {
                diagnosticLogger.warn(DiagnosticCategory.SECURITY, "DB_DECRYPTION_FAILED", mapOf("reason" to (e.message ?: "unknown")))
                
                // If decryption fails (likely device change) and we have a wrapper, STOP and require recovery.
                if (recoveryWrapper != null) {
                    return@withContext PreloadResult.RecoveryRequired
                }
                
                // Legacy / No recovery phrase saved: Fallback to renaming and fresh start.
                cachedPassphrase = generateAndStoreNewPassphrase()
            }

            PreloadResult.Success
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_PRELOAD_FATAL_ERROR", throwable = e)
            PreloadResult.FatalFailure(e)
        }
    }

    /**
     * Attempts to recover the database passphrase using a mnemonic phrase.
     * If successful, the passphrase is re-bound to the current device's Keystore.
     */
    suspend fun tryRecovery(mnemonic: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val prefs = dataStore.data.first()
            val wrapper = prefs[RECOVERY_WRAPPER_KEY] ?: return@withContext Result.failure(IllegalStateException("No recovery wrapper found"))
            
            // 1. Derive Recovery Key
            val salt = RECOVERY_SALT.toByteArray(Charsets.UTF_8)
            val recoveryKeyBytes = SecurityArchitecture.deriveKey(mnemonic, salt)
            
            // 2. Unwrap Passphrase
            val cipher = AesGcmJce(recoveryKeyBytes)
            val encryptedBytes = Base64.decode(wrapper, Base64.DEFAULT)
            val rawPassphrase = cipher.decrypt(encryptedBytes, null)
            
            // 3. Verify Passphrase against restored database
            if (!validatePassphrase(rawPassphrase)) {
                return@withContext Result.failure(Exception("Incorrect recovery phrase or database mismatch"))
            }
            
            // 4. Re-bind to current device Keystore
            saveEncryptedPassphrase(rawPassphrase)
            cachedPassphrase = rawPassphrase
            
            diagnosticLogger.info(DiagnosticCategory.SECURITY, "DB_RECOVERY_SUCCESS")
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_RECOVERY_FAILED", throwable = e)
            Result.failure(e)
        }
    }

    /**
     * Creates a secondary recovery wrapper for the current database passphrase.
     * This wrapper is backed by a key derived from the mnemonic and can be 
     * used to restore access on a different device.
     */
    suspend fun createRecoveryWrapper(mnemonic: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentPassphrase = getDatabasePassphrase()
            
            val salt = RECOVERY_SALT.toByteArray(Charsets.UTF_8)
            val recoveryKeyBytes = SecurityArchitecture.deriveKey(mnemonic, salt)
            
            val cipher = AesGcmJce(recoveryKeyBytes)
            val wrapped = cipher.encrypt(currentPassphrase, null)
            val encoded = Base64.encodeToString(wrapped, Base64.DEFAULT)
            
            dataStore.edit { preferences ->
                preferences[RECOVERY_WRAPPER_KEY] = encoded
                preferences[RECOVERY_VERSION_KEY] = CURRENT_RECOVERY_VERSION
            }
            
            diagnosticLogger.info(DiagnosticCategory.SECURITY, "DB_RECOVERY_WRAPPER_CREATED")
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_WRAPPER_CREATION_FAILED", throwable = e)
            Result.failure(e)
        }
    }

    /**
     * Gets or creates a high-entropy passphrase for the database.
     * 
     * WARNING: If [preload] has not been called, this will trigger a synchronous
     * runBlocking operation which may cause ANRs on the Main Thread.
     */
    fun getDatabasePassphrase(): ByteArray {
        cachedPassphrase?.let { return it }

        diagnosticLogger.warn(DiagnosticCategory.SECURITY, "DB_PASSPHRASE_SYNC_FETCH", mapOf("thread" to Thread.currentThread().name))

        val passphrase = runBlocking {
            val prefs = dataStore.data.first()
            val encryptedPassphrase = prefs[DB_PASSPHRASE_KEY]
            val recoveryWrapper = prefs[RECOVERY_WRAPPER_KEY]

            if (encryptedPassphrase != null) {
                try {
                    val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
                    val decrypted = googleAEAD.decrypt(encryptedBytes, null)
                    
                    if (!validatePassphrase(decrypted)) {
                        diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_PASSPHRASE_VALIDATION_FAILED")
                        
                        // SAFETY LOCK: If recovery is possible, DO NOT wipe.
                        if (recoveryWrapper != null) {
                            throw DatabaseLockedException("Database locked: validation failed but recovery exists.")
                        }
                        
                        generateAndStoreNewPassphrase()
                    } else {
                        decrypted
                    }
                } catch (e: Exception) {
                    if (e is DatabaseLockedException) throw e
                    
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_DECRYPTION_FAILED", throwable = e)
                    
                    // SAFETY LOCK: If decryption failed (e.g. Keystore invalidation) but recovery is possible, DO NOT wipe.
                    if (recoveryWrapper != null) {
                        throw DatabaseLockedException("Database locked: decryption failed but recovery exists.")
                    }
                    
                    generateAndStoreNewPassphrase()
                }
            } else {
                // If we have a recovery wrapper but no passphrase, we are in a RESTORE state.
                // Room initialization must be blocked until tryRecovery() re-binds the passphrase.
                if (recoveryWrapper != null) {
                    throw DatabaseLockedException("Database locked: restore pending.")
                }
                
                generateAndStoreNewPassphrase()
            }
        }
        
        cachedPassphrase = passphrase
        return passphrase
    }

    /**
     * Re-encrypts the existing passphrase with the current TINK Master Key.
     * Use this when TINK keys have rotated but the database passphrase itself is fine.
     */
    fun refreshEncryptionMetadata(): Result<Unit> {
        return try {
            val currentPassphrase = getDatabasePassphrase()
            runBlocking {
                saveEncryptedPassphrase(currentPassphrase)
            }
            diagnosticLogger.info(DiagnosticCategory.SECURITY, "DB_ENCRYPTION_METADATA_REFRESHED")
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_ENCRYPTION_METADATA_REFRESH_FAILED", throwable = e)
            Result.failure(e)
        }
    }

    internal suspend fun saveEncryptedPassphrase(passphrase: ByteArray) {
        val encryptedBytes = googleAEAD.encrypt(passphrase, null)
        val encryptedEncoded = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        
        dataStore.edit { preferences ->
            preferences[DB_PASSPHRASE_KEY] = encryptedEncoded
        }
    }

    private fun generateSecureRandomPassphrase(): ByteArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Attempts to open the database with the given passphrase to verify its validity.
     */
    internal fun validatePassphrase(passphrase: ByteArray): Boolean {
        val dbFile = context.getDatabasePath("artifact_db")
        if (!dbFile.exists()) return true // No database yet, passphrase is "valid"

        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null
            ).use { db ->
                db.rawExecSQL("SELECT COUNT(*) FROM sqlite_schema")
            }
            true
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_VALIDATION_ERROR", throwable = e)
            false
        }
    }

    internal suspend fun generateAndStoreNewPassphrase(): ByteArray {
        val newPassphrase = generateSecureRandomPassphrase()
        saveEncryptedPassphrase(newPassphrase)

        // CRITICAL: If we've generated a NEW passphrase, the old database file is now
        // permanently unrecoverable by the current app instance.
        // We RENAME it instead of deleting it for safety.
        renameCorruptedDatabase()
        
        return newPassphrase
    }

    /**
     * Renames the local database files to "corrupted" to allow for recovery and avoid blocking the app.
     */
    internal fun renameCorruptedDatabase() {
        try {
            val dbFile = context.getDatabasePath("artifact_db")
            if (dbFile.exists()) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val corruptedDbFile = File(dbFile.absolutePath + "_corrupted_$timestamp")
                dbFile.renameTo(corruptedDbFile)
                
                // Also rename sidecar files if they exist
                renameFile(File(dbFile.absolutePath + "-journal"), timestamp)
                renameFile(File(dbFile.absolutePath + "-shm"), timestamp)
                renameFile(File(dbFile.absolutePath + "-wal"), timestamp)
                
                diagnosticLogger.warn(DiagnosticCategory.SECURITY, "DB_CORRUPTED_RENAMED", mapOf("newName" to corruptedDbFile.name))
            }
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_RENAME_FAILED", throwable = e)
        }
    }

    private fun renameFile(file: File, timestamp: String) {
        if (file.exists()) {
            file.renameTo(File(file.absolutePath + "_corrupted_$timestamp"))
        }
    }

    /**
     * Creates a lazy SQLCipher helper factory with the persistent passphrase.
     * Passphrase acquisition is deferred until Room physically opens the database connection on a background thread.
     */
    fun getEncryptionFactory(): SupportOpenHelperFactory {
        return LazySupportOpenHelperFactory { getDatabasePassphrase() }
    }

    /**
     * Clears the in-memory passphrase cache and local DataStore.
     * Essential for maintaining account boundaries during logout.
     */
    suspend fun clear() {
        cachedPassphrase = null
        dataStore.edit { it.clear() }
    }

    companion object {
        private val DB_PASSPHRASE_KEY = stringPreferencesKey("db_passphrase")
        private val RECOVERY_WRAPPER_KEY = stringPreferencesKey("recovery_passphrase_wrapper")
        private val RECOVERY_VERSION_KEY = intPreferencesKey("recovery_version")
        
        private const val RECOVERY_SALT = "artifact_db_recovery_v1"
        private const val CURRENT_RECOVERY_VERSION = 1
    }
}

private class LazySupportOpenHelperFactory(
    private val passphraseProvider: () -> ByteArray
) : SupportOpenHelperFactory(byteArrayOf()) {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        return SupportOpenHelperFactory(passphraseProvider()).create(configuration)
    }
}
