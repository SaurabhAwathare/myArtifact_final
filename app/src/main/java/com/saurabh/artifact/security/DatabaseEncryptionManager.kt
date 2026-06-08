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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "db_encryption_prefs")

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val startupCoordinator: StartupCoordinator
) {
    private val googleAead: Aead by lazy {
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
     * The passphrase is encrypted using Google Tink and stored in DataStore.
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
                    val passphrase = googleAead.decrypt(encryptedBytes, null)
                    
                    // VALIDATION: Check if this passphrase can actually open the database
                    if (!validatePassphrase(passphrase)) {
                        android.util.Log.e("DatabaseEncryption", "Passphrase validation failed, generating new one")
                        generateAndStoreNewPassphrase()
                    } else {
                        passphrase
                    }
                } catch (_: Exception) {
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
     * Attempts to open the database with the given passphrase to verify its validity.
     */
    private fun validatePassphrase(passphrase: ByteArray): Boolean {
        val dbFile = context.getDatabasePath("artifact_db")
        if (!dbFile.exists()) return true // No database yet, passphrase is "valid"

        var db: net.zetetic.database.sqlcipher.SQLiteDatabase? = null
        return try {
            db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphrase,
                null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
                null
            )
            db?.rawExecSQL("SELECT COUNT(*) FROM sqlite_schema")
            true
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Database validation error: ${e.message}")
            false
        } finally {
            db?.close()
        }
    }

    private suspend fun generateAndStoreNewPassphrase(): ByteArray {
        val newPassphrase = ByteArray(32)
        SecureRandom().nextBytes(newPassphrase)
        
        val encryptedBytes = googleAead.encrypt(newPassphrase, null)
        val encryptedEncoded = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        
        context.dataStore.edit { preferences ->
            preferences[DB_PASSPHRASE_KEY] = encryptedEncoded
        }

        // CRITICAL: If we've generated a NEW passphrase, the old database file is now
        // permanently unrecoverable. We MUST delete it to avoid 'file is not a database'
        // (code 26) errors on the next open attempt.
        deleteDatabaseFiles()
        
        return newPassphrase
    }

    /**
     * Deletes the local database files to recover from corruption or key loss.
     */
    fun deleteDatabaseFiles() {
        try {
            val dbFile = context.getDatabasePath("artifact_db")
            if (dbFile.exists()) {
                dbFile.delete()
                // Also delete journal/shm/wal files if they exist
                context.getDatabasePath("artifact_db-journal").delete()
                context.getDatabasePath("artifact_db-shm").delete()
                context.getDatabasePath("artifact_db-wal").delete()
                android.util.Log.w("DatabaseEncryption", "Deleted database files for recovery")
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseEncryption", "Failed to delete database", e)
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
