package com.saurabh.artifact.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.util.Base64
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("db_encryption_prefs", Context.MODE_PRIVATE)
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)
            .build()
    }

    /**
     * Gets or creates a high-entropy passphrase for the database.
     * The passphrase is stored in encrypted SharedPreferences or similar (using MasterKey).
     */
    fun getDatabasePassphrase(): ByteArray {
        val encodedPassphrase = prefs.getString(DB_PASSPHRASE_KEY, null)
        return if (encodedPassphrase != null) {
            Base64.decode(encodedPassphrase, Base64.DEFAULT)
        } else {
            val newPassphrase = ByteArray(32)
            SecureRandom().nextBytes(newPassphrase)
            val newEncoded = Base64.encodeToString(newPassphrase, Base64.DEFAULT)
            prefs.edit { putString(DB_PASSPHRASE_KEY, newEncoded) }
            newPassphrase
        }
    }

    /**
     * Creates a SQLCipher helper factory with the persistent passphrase.
     */
    fun getEncryptionFactory(): SupportOpenHelperFactory {
        return SupportOpenHelperFactory(getDatabasePassphrase())
    }

    companion object {
        private const val DB_PASSPHRASE_KEY = "db_passphrase"
    }
}
