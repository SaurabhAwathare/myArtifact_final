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
    @param:ApplicationContext private val context: Context
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

    /**
     * Gets or creates a high-entropy passphrase for the database.
     * The passphrase is encrypted using Google Tink and stored in DataStore.
     */
    fun getDatabasePassphrase(): ByteArray {
        return runBlocking {
            val encryptedPassphrase = context.dataStore.data
                .map { preferences -> preferences[DB_PASSPHRASE_KEY] }
                .first()

            if (encryptedPassphrase != null) {
                try {
                    val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.DEFAULT)
                    googleAead.decrypt(encryptedBytes, null)
                } catch (_: Exception) {
                    // If decryption fails (e.g., keyset changed), generate new one
                    generateAndStoreNewPassphrase()
                }
            } else {
                generateAndStoreNewPassphrase()
            }
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
        
        return newPassphrase
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
