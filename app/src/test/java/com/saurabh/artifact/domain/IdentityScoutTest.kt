package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.util.SecureString
import org.junit.Assert.*
import org.junit.Test

class IdentityScoutTest {

    private val scout = IdentityScout()

    @Test
    fun `detect real name in username`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val warnings = scout.detectLeaks("saurabh_98", realName, null)
        
        assertTrue(warnings.any { it.reason == ValidationReason.REAL_NAME })
    }

    @Test
    fun `detect partial name in username`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val warnings = scout.detectLeaks("awathare_x", realName, null)
        
        assertTrue(warnings.any { it.reason == ValidationReason.REAL_NAME })
    }

    @Test
    fun `detect email prefix in username`() {
        val email = SecureString.fromString("saurabh.music@gmail.com")
        val warnings = scout.detectLeaks("saurabhmusic_relic", null, email)
        
        assertTrue(warnings.any { it.reason == ValidationReason.EMAIL_ADDRESS })
    }

    @Test
    fun `detect phone number pattern`() {
        val warnings = scout.detectLeaks("user9876543210", null, null)
        
        assertTrue(warnings.any { it.reason == ValidationReason.PHONE_NUMBER })
    }

    @Test
    fun `no leaks in anonymous name`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val warnings = scout.detectLeaks("Quiet Lantern", realName, SecureString.fromString("saurabh@gmail.com"))
        
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `detect motif reuse through phonetic similarity`() {
        val realName = SecureString.fromString("Saurabh")
        // "Saurya" -> normalized "saurya"
        // "Saurabh" -> normalized "saurabh"
        // Distance between "saurya" and "saurabh":
        // s a u r y a
        // s a u r a b h
        // y->a, a->b, +h = 3
        // Let's try "Saurab" -> distance 1
        val warnings = scout.detectLeaks("Saurab_42", realName, null)
        
        assertTrue("Should detect motif reuse for phonetic similarity", 
            warnings.any { it.reason == ValidationReason.MOTIF_REUSE })
    }

    @Test
    fun `detect introduction pattern in transcript`() {
        val transcript = "Hi, my name is Alex and I wanted to share my story."
        val warnings = scout.detectLeaks(transcript, null, null)
        
        assertTrue("Should detect introduction pattern", 
            warnings.any { it.reason == ValidationReason.INTRODUCTION_PATTERN })
    }

    @Test
    fun `detect contact pivot in transcript`() {
        val transcript = "You can follow me on Instagram @alex_stories for more."
        val warnings = scout.detectLeaks(transcript, null, null)
        
        assertTrue("Should detect contact pivot pattern", 
            warnings.any { it.reason == ValidationReason.CONTACT_PIVOT })
    }

    @Test
    fun `calculate risk score correctly`() {
        val warnings = listOf(
            com.saurabh.artifact.model.ModerationWarning(ValidationReason.REAL_NAME, "Leak"),
            com.saurabh.artifact.model.ModerationWarning(ValidationReason.INTRODUCTION_PATTERN, "Intro")
        )
        val score = scout.calculateRiskScore(warnings)
        
        // 0.8 (Real Name) + 0.5 (Intro) = 1.3 -> Coerced to 1.0
        assertEquals(1.0f, score)
    }

    @Test
    fun `calculate low risk score for single behavioral leak`() {
        val warnings = listOf(
            com.saurabh.artifact.model.ModerationWarning(ValidationReason.INTRODUCTION_PATTERN, "Intro")
        )
        val score = scout.calculateRiskScore(warnings)
        
        assertEquals(0.5f, score)
    }
}
