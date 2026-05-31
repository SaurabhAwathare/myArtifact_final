package com.saurabh.artifact.security

import cash.z.ecc.android.bip39.Mnemonics
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Generates and validates BIP-39 mnemonic phrases for recovery.
 */
object MnemonicGenerator {

    /**
     * Generates a new 12-word mnemonic phrase.
     */
    fun generateMnemonic(): List<String> {
        val entropy = ByteArray(16) // 128 bits for 12 words
        SecureRandom().nextBytes(entropy)
        return Mnemonics.MnemonicCode(entropy).words.map { String(it) }
    }

    /**
     * Validates a mnemonic phrase.
     */
    fun validateMnemonic(words: List<String>): Boolean {
        return try {
            Mnemonics.MnemonicCode(words.joinToString(" ")).validate()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts a mnemonic phrase to a deterministic seed for key derivation.
     * Follows BIP-39 standard: PBKDF2 with 2048 iterations and HMAC-SHA512.
     */
    fun toSeed(words: List<String>, passphrase: String = ""): ByteArray {
        val mnemonic = words.joinToString(" ")
        val salt = "mnemonic$passphrase"
        val iterations = 2048
        val keyLength = 512
        val spec = PBEKeySpec(mnemonic.toCharArray(), salt.toByteArray(), iterations, keyLength)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return skf.generateSecret(spec).encoded
    }
}
