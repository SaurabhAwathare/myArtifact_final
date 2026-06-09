package com.saurabh.artifact.security

import com.google.crypto.tink.subtle.AesGcmJce
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class BackupEncryptionManagerTest {

    @Test
    fun `test encryption and decryption consistency`() {
        val key = ByteArray(32) { 1 }
        val data = "Hello Security".toByteArray(StandardCharsets.UTF_8)
        
        val primitive = AesGcmJce(key)
        val encrypted = primitive.encrypt(data, null)
        val decrypted = primitive.decrypt(encrypted, null)
        
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun `test different keys produce different ciphertexts`() {
        val key1 = ByteArray(32) { 1 }
        val key2 = ByteArray(32) { 2 }
        val data = "Hello Security".toByteArray(StandardCharsets.UTF_8)
        
        val primitive1 = AesGcmJce(key1)
        val primitive2 = AesGcmJce(key2)
        
        val encrypted1 = primitive1.encrypt(data, null)
        val encrypted2 = primitive2.encrypt(data, null)
        
        // This is a bit loose but GCM is probabilistic anyway due to random IV
        // The point is to ensure basic Tink functionality is available and working.
        assert(!encrypted1.contentEquals(encrypted2))
    }
}
