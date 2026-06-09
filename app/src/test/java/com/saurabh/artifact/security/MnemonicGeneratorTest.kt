package com.saurabh.artifact.security

import org.junit.Assert.*
import org.junit.Test

class MnemonicGeneratorTest {

    @Test
    fun `generateMnemonic produces unique 12-word phrases`() {
        val mnemonic1 = MnemonicCodeHelper.generate()
        val mnemonic2 = MnemonicCodeHelper.generate()
        
        assertEquals(12, mnemonic1.size)
        assertEquals(12, mnemonic2.size)
        assertNotEquals(mnemonic1, mnemonic2)
    }

    @Test
    fun `validateMnemonic returns true for generated phrases`() {
        val mnemonic = MnemonicGenerator.generateMnemonic()
        assertTrue(MnemonicGenerator.validateMnemonic(mnemonic))
    }

    @Test
    fun `validateMnemonic returns false for tampered phrases`() {
        val mnemonic = MnemonicGenerator.generateMnemonic().toMutableList()
        // Change one word
        mnemonic[0] = if (mnemonic[0] == "abandon") "ability" else "abandon"
        
        assertFalse(MnemonicGenerator.validateMnemonic(mnemonic))
    }

    @Test
    fun `toSeed produces consistent 512-bit seed`() {
        val mnemonic = listOf("abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "about")
        val seed1 = MnemonicGenerator.toSeed(mnemonic, "passphrase")
        val seed2 = MnemonicGenerator.toSeed(mnemonic, "passphrase")
        
        assertEquals(64, seed1.size) // 512 bits = 64 bytes
        assertArrayEquals(seed1, seed2)
    }

    @Test
    fun `toSeed follows BIP-39 test vector`() {
        // Test vector from BIP-39 spec
        // Entropy: 00000000000000000000000000000000
        // Mnemonic: abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
        // Passphrase: TREZOR
        // Seed: c5525984ee6011a88981930b4f96472da434c0b22a07c67c3a0ee2d7003854e05b9748669c8574a8791d30779310c8385a4f788a79402283e58ca495d4870f7d
        
        val mnemonic = listOf("abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "about")
        val passphrase = "TREZOR"
        val expectedSeedHex = "c5525984ee6011a88981930b4f96472da434c0b22a07c67c3a0ee2d7003854e05b9748669c8574a8791d30779310c8385a4f788a79402283e58ca495d4870f7d"
        
        val actualSeed = MnemonicGenerator.toSeed(mnemonic, passphrase)
        val actualSeedHex = actualSeed.joinToString("") { "%02x".format(it) }
        
        assertEquals(expectedSeedHex, actualSeedHex)
    }

    private object MnemonicCodeHelper {
        fun generate() = MnemonicGenerator.generateMnemonic()
    }
}
