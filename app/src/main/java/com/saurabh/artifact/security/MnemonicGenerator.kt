package com.saurabh.artifact.security

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Generates and validates BIP-39 mnemonic phrases for recovery.
 */
object MnemonicGenerator {

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
