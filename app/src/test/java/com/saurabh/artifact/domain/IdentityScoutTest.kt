package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ModerationWarning
import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.repository.NameCorpusRepository
import com.saurabh.artifact.util.SecureString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityScoutTest {

    private val testCorpusRepository = NameCorpusRepository(
        setOf("saurabh", "awathare", "alexander", "priya", "catarina")
    )

    private val scout = IdentityScout(nameCorpusRepository = testCorpusRepository)

    // --- PHASE 4: Offline Name Corpus Tests ---

    @Test
    fun `detect standalone corpus given name Saurabh in username is blocking`() {
        val warnings = scout.detectLeaks("Saurabh", null, null)

        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue("Saurabh must trigger real name warning", warning != null)
        assertTrue("Corpus real name warning must be blocking", warning!!.isBlocking)
    }

    @Test
    fun `detect standalone corpus surname Awathare in username is blocking`() {
        val warnings = scout.detectLeaks("Awathare", null, null)

        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue("Awathare must trigger real name warning", warning != null)
        assertTrue("Corpus surname warning must be blocking", warning!!.isBlocking)
    }

    @Test
    fun `detect case insensitive corpus name matching`() {
        val warnings1 = scout.detectLeaks("SAURABH", null, null)
        val warnings2 = scout.detectLeaks("aWaThArE", null, null)

        assertTrue(warnings1.any { it.reason == ValidationReason.REAL_NAME && it.isBlocking })
        assertTrue(warnings2.any { it.reason == ValidationReason.REAL_NAME && it.isBlocking })
    }

    @Test
    fun `detect corpus name with digit suffix saurabh_98 is blocking`() {
        val warnings = scout.detectLeaks("saurabh_98", null, null)

        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue(warning != null && warning.isBlocking)
    }

    @Test
    fun `detect dotted corpus name saurabh dot awathare is blocking`() {
        val warnings = scout.detectLeaks("saurabh.awathare", null, null)

        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue(warning != null && warning.isBlocking)
    }

    @Test
    fun `detect camelCase corpus name SaurabhAwathare is blocking`() {
        val warnings = scout.detectLeaks("SaurabhAwathare", null, null)

        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue(warning != null && warning.isBlocking)
    }

    @Test
    fun `detect delimiter variations hyphen space middle dot`() {
        val inputs = listOf(
            "saurabh-awathare",
            "saurabh awathare",
            "saurabh·awathare"
        )

        for (input in inputs) {
            val warnings = scout.detectLeaks(input, null, null)
            val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
            assertTrue("Input '$input' must trigger blocking REAL_NAME", warning != null && warning.isBlocking)
        }
    }

    @Test
    fun `substring protection - artist does not match art`() {
        val warnings = scout.detectLeaks("artist", null, null)

        val hasBlockingRealName = warnings.any { it.reason == ValidationReason.REAL_NAME && it.isBlocking }
        assertFalse("'artist' must not trigger a blocking real name match for 'art'", hasBlockingRealName)
    }

    @Test
    fun `substring protection - catarina does not trigger false positive for cat`() {
        val warnings = scout.detectLeaks("catarina_relic", null, null)

        // 'catarina' is in corpus, so it should trigger REAL_NAME for 'catarina', but NOT because of 'cat'
        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue(warning != null && warning.isBlocking)
    }

    @Test
    fun `dual-meaning words do not trigger blocking real name leaks`() {
        val dualMeaningInputs = listOf("Amber", "Rose", "King", "Hope", "Faith")

        for (input in dualMeaningInputs) {
            val warnings = scout.detectLeaks(input, null, null)
            val hasBlockingRealName = warnings.any { it.reason == ValidationReason.REAL_NAME && it.isBlocking }
            assertFalse("Dual-meaning word '$input' must not trigger blocking REAL_NAME leak", hasBlockingRealName)
        }
    }

    @Test
    fun `atmospheric handles remain non-blocking pseudonyms`() {
        val atmosphericHandles = listOf(
            "Misty Rose",
            "Ray of Hope",
            "Miles Ahead",
            "Quiet River"
        )

        for (handle in atmosphericHandles) {
            val warnings = scout.detectLeaks(handle, null, null)
            val blockingWarnings = warnings.filter { it.isBlocking }
            assertTrue("Atmospheric handle '$handle' should have no blocking warnings, found: $blockingWarnings", blockingWarnings.isEmpty())
        }
    }

    // --- Preservation of Existing IdentityScout Tests ---

    @Test
    fun `detect real name in username is blocking`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val warnings = scout.detectLeaks("saurabh_98", realName, null)
        
        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue("Should detect real name leak", warning != null)
        assertTrue("Real name match must be blocking", warning!!.isBlocking)
    }

    @Test
    fun `detect partial real name in username is blocking`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val warnings = scout.detectLeaks("awathare_x", realName, null)
        
        val warning = warnings.find { it.reason == ValidationReason.REAL_NAME }
        assertTrue("Should detect real name leak", warning != null)
        assertTrue("Partial real name match must be blocking", warning!!.isBlocking)
    }

    @Test
    fun `detect email prefix in username is blocking`() {
        val email = SecureString.fromString("saurabh.music@gmail.com")
        val warnings = scout.detectLeaks("saurabhmusic_relic", null, email)
        
        val warning = warnings.find { it.reason == ValidationReason.EMAIL_ADDRESS }
        assertTrue("Should detect email prefix leak", warning != null)
        assertTrue("Email prefix match must be blocking", warning!!.isBlocking)
    }

    @Test
    fun `detect phone number pattern is blocking`() {
        val warnings = scout.detectLeaks("user9876543210", null, null)
        
        val warning = warnings.find { it.reason == ValidationReason.PHONE_NUMBER }
        assertTrue("Should detect phone number pattern", warning != null)
        assertTrue("Phone number leak must be blocking", warning!!.isBlocking)
    }

    @Test
    fun `no leaks in anonymous name`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val warnings = scout.detectLeaks("Quiet Lantern", realName, SecureString.fromString("saurabh@gmail.com"))
        
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `detect motif reuse through phonetic similarity as advisory`() {
        val realName = SecureString.fromString("Saurabh")
        val warnings = scout.detectLeaks("Saurab_42", realName, null)
        
        val warning = warnings.find { it.reason == ValidationReason.MOTIF_REUSE }
        assertTrue("Should detect motif reuse for phonetic similarity", warning != null)
        assertFalse("Motif reuse must be advisory (non-blocking)", warning!!.isBlocking)
    }

    @Test
    fun `detect introduction pattern as advisory`() {
        val input = "Hi, my name is Alex and I wanted to share my story."
        val warnings = scout.detectLeaks(input, null, null)
        
        val warning = warnings.find { it.reason == ValidationReason.INTRODUCTION_PATTERN }
        assertTrue("Should detect introduction pattern", warning != null)
        assertFalse("Introduction pattern must be advisory (non-blocking)", warning!!.isBlocking)
    }

    @Test
    fun `detect contact pivot as advisory`() {
        val input = "You can follow me on Instagram @alex_stories for more."
        val warnings = scout.detectLeaks(input, null, null)
        
        val warning = warnings.find { it.reason == ValidationReason.CONTACT_PIVOT }
        assertTrue("Should detect contact pivot pattern", warning != null)
        assertFalse("Contact pivot must be advisory (non-blocking)", warning!!.isBlocking)
    }

    @Test
    fun `detect potentially identifying handles as advisory`() {
        val warnings = scout.detectLeaks("real_john_doe", null, null)
        
        val warning = warnings.find { it.reason == ValidationReason.POTENTIALLY_IDENTIFYING }
        assertTrue("Should detect potentially identifying handle prefix", warning != null)
        assertFalse("Potentially identifying handle warning must be advisory", warning!!.isBlocking)
    }

    @Test
    fun `calculate risk score correctly`() {
        val warnings = listOf(
            ModerationWarning(ValidationReason.REAL_NAME, "Leak", isBlocking = true),
            ModerationWarning(ValidationReason.INTRODUCTION_PATTERN, "Intro", isBlocking = false)
        )
        val score = scout.calculateRiskScore(warnings)
        
        assertEquals(1.0f, score)
    }

    @Test
    fun `calculate low risk score for single behavioral leak`() {
        val warnings = listOf(
            ModerationWarning(ValidationReason.INTRODUCTION_PATTERN, "Intro", isBlocking = false)
        )
        val score = scout.calculateRiskScore(warnings)
        
        assertEquals(0.5f, score)
    }

    @Test
    fun `application role words in display name do not trigger false positive`() {
        val roleName = SecureString.fromString("Artifact Creator")
        val warnings = scout.detectLeaks("creator publish an artifact", roleName, null)
        
        assertTrue("Role words like 'Creator' and 'Artifact' should not be flagged as real name leaks",
            warnings.none { it.reason == ValidationReason.REAL_NAME || it.reason == ValidationReason.MOTIF_REUSE })
    }

    @Test
    fun `genuine personal name in title is detected when combined with role name`() {
        val realName = SecureString.fromString("Saurabh Creator")
        val warnings = scout.detectLeaks("creator publish an artifact by Saurabh", realName, null)
        
        assertTrue("Genuine personal name 'Saurabh' should still be detected",
            warnings.any { it.reason == ValidationReason.REAL_NAME })
    }
}
