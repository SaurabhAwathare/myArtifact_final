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
        // Use a word that is definitely NOT in the BIP-39 English wordlist
        mnemonic[0] = "xyz123invalid"
        
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
        // Seed: c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04
        
        val mnemonic = listOf("abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "about")
        val passphrase = "TREZOR"
        val expectedSeedHex = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
        
        val actualSeed = MnemonicGenerator.toSeed(mnemonic, passphrase)
        val actualSeedHex = actualSeed.joinToString("") { "%02x".format(it) }
        
        assertEquals(expectedSeedHex, actualSeedHex)
    }

    private object MnemonicCodeHelper {
        fun generate() = MnemonicGenerator.generateMnemonic()
    }
}
