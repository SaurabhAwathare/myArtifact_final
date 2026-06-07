package com.saurabh.artifact.security

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityArchitecture {

    private fun getStreamingAead(context: Context): StreamingAead {
        StreamingAeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, "streaming_master_keyset", "streaming_master_key_preference")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .withMasterKeyUri("android-keystore://streaming_master_key")
            .build()
            .keysetHandle
            .getPrimitive(StreamingAead::class.java)
    }

    /**
     * Returns a decrypting input stream for the given file.
     */
    fun openDecryptingStream(context: Context, file: File): InputStream {
        val streamingAead = getStreamingAead(context)
        return streamingAead.newDecryptingStream(file.inputStream(), null)
    }

    /**
     * Returns an encrypting output stream for the given file.
     */
    fun openEncryptingStream(context: Context, file: File): OutputStream {
        val streamingAead = getStreamingAead(context)
        return streamingAead.newEncryptingStream(file.outputStream(), null)
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
}
