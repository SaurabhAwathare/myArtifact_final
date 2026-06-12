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
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
    private val startupCoordinator: StartupCoordinator
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

    private var cachedPassphrase: ByteArray? = null

    /**
     * Gets or creates a high-entropy passphrase for the database.
     * The passphrase is encrypted using Google TINK and stored in DataStore.
     */
    fun getDatabasePassphrase(): ByteArray {
        cachedPassphrase?.let { return it }

        val passphrase = runBlocking {
            val encryptedPassphrase = context.dataStore.data
                .map { preferences -> preferences[DB_PASSPHRASE_KEY] }
                .first()

            if (encryptedPassphrase != null) {
                try {
                    val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
                    val passphrase = googleAEAD.decrypt(encryptedBytes, null)
                    
                    // VALIDATION: Check if this passphrase can actually open the database
                    if (!validatePassphrase(passphrase)) {
                        android.util.Log.e("DatabaseEncryption", "Passphrase validation failed, attempting recovery")
                        // If validation fails, it might be a genuinely corrupted DB or wrong key.
                        // We generate a new one, but keep the old DB renamed.
                        generateAndStoreNewPassphrase()
                    } else {
                        passphrase
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DatabaseEncryption", "Decryption failed: ${e.message}", e)
                    // If decryption fails (e.g., keyset changed), generate new one
                    generateAndStoreNewPassphrase()
                }
            } else {
                generateAndStoreNewPassphrase()
            }
        }
        
        // Signal that the database encryption/access is ready
        startupCoordinator.emitReadiness(StartupComponent.DATABASE)
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
            android.util.Log.i("DatabaseEncryption", "Encryption metadata refreshed")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Refresh failed", e)
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
            android.util.Log.e("DatabaseEncryption", "Database validation error: ${e.message}")
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
                
                android.util.Log.w("DatabaseEncryption", "Renamed corrupted database to ${corruptedDbFile.name}")
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to rename corrupted database", e)
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
