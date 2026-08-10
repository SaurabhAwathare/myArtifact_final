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
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "db_encryption_prefs")

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
     * Preloads and validates the database passphrase on a background thread.
     * Must be called during the startup sequence before the database is accessed.
     */
    suspend fun preload(): Result<Unit> = withContext(Dispatchers.IO) {
        if (cachedPassphrase != null) return@withContext Result.success(Unit)

        try {
            val encryptedPassphrase = context.dataStore.data
                .map { preferences -> preferences[DB_PASSPHRASE_KEY] }
                .first()

            val passphrase = if (encryptedPassphrase != null) {
                try {
                    val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
                    val decrypted = googleAEAD.decrypt(encryptedBytes, null)
                    
                    // VALIDATION: Check if this passphrase can actually open the database
                    if (!validatePassphrase(decrypted)) {
                        diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_PASSPHRASE_VALIDATION_FAILED")
                        generateAndStoreNewPassphrase()
                    } else {
                        decrypted
                    }
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_DECRYPTION_FAILED", throwable = e)
                    generateAndStoreNewPassphrase()
                }
            } else {
                generateAndStoreNewPassphrase()
            }

            cachedPassphrase = passphrase
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_PRELOAD_FATAL_ERROR", throwable = e)
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
            val encryptedPassphrase = context.dataStore.data
                .map { preferences -> preferences[DB_PASSPHRASE_KEY] }
                .first()

            if (encryptedPassphrase != null) {
                try {
                    val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
                    val passphrase = googleAEAD.decrypt(encryptedBytes, null)
                    
                    if (!validatePassphrase(passphrase)) {
                        diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_PASSPHRASE_VALIDATION_FAILED")
                        generateAndStoreNewPassphrase()
                    } else {
                        passphrase
                    }
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "DB_DECRYPTION_FAILED", throwable = e)
                    generateAndStoreNewPassphrase()
                }
            } else {
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

    private suspend fun saveEncryptedPassphrase(passphrase: ByteArray) {
        val encryptedBytes = googleAEAD.encrypt(passphrase, null)
        val encryptedEncoded = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        
        context.dataStore.edit { preferences ->
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
    private fun validatePassphrase(passphrase: ByteArray): Boolean {
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

    private suspend fun generateAndStoreNewPassphrase(): ByteArray {
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
    private fun renameCorruptedDatabase() {
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
     * Creates a SQLCipher helper factory with the persistent passphrase.
     */
    fun getEncryptionFactory(): SupportOpenHelperFactory {
        return SupportOpenHelperFactory(getDatabasePassphrase())
    }

    companion object {
        private val DB_PASSPHRASE_KEY = stringPreferencesKey("db_passphrase")
    }
}
