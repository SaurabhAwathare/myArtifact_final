package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.util.SecureString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameValidatorTest {

    private val validator = UsernameValidator(IdentityScout())

    @Test
    fun `test valid usernames`() {
        val result = validator.validate("quiet_river_42")
        assertTrue(result.isValid)
        assertFalse(result.hasBlockingError)
        assertFalse(result.hasAdvisoryWarnings)
        assertNull(result.reason)
    }

    @Test
    fun `test generated username containing space is accepted`() {
        val result = validator.validate("Quiet Path")
        assertTrue(result.isValid)
        assertFalse(result.hasBlockingError)
        assertNull(result.reason)
    }

    @Test
    fun `test generated username containing supported middle dot character is handled consistently`() {
        val result = validator.validate("Quiet Path · A7")
        assertTrue(result.isValid)
        assertFalse(result.hasBlockingError)
        assertNull(result.reason)
    }

    @Test
    fun `test max length 30 characters accepted`() {
        val valid30 = "a".repeat(30)
        val result30 = validator.validate(valid30)
        assertTrue(result30.isValid)

        val invalid31 = "a".repeat(31)
        val result31 = validator.validate(invalid31)
        assertFalse(result31.isValid)
        assertTrue(result31.hasBlockingError)
        assertEquals(ValidationReason.TOO_LONG, result31.reason)
    }

    @Test
    fun `test too short username is blocking`() {
        val result = validator.validate("hi")
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.TOO_SHORT, result.reason)
    }

    @Test
    fun `test invalid characters is blocking`() {
        val result = validator.validate("invalid_name!")
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.INVALID_CHARACTERS, result.reason)
    }

    @Test
    fun `test email detection remains blocking`() {
        val result = validator.validate("user@gmail.com")
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.EMAIL_ADDRESS, result.reason)
    }

    @Test
    fun `test phone number detection remains blocking`() {
        val result = validator.validate("user9876543210")
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.PHONE_NUMBER, result.reason)
    }

    @Test
    fun `test real name match remains blocking`() {
        val realName = SecureString.fromString("Saurabh Awathare")
        val result = validator.validate("saurabh_98", realName = realName)
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.REAL_NAME, result.reason)
    }

    @Test
    fun `test reserved name remains blocking`() {
        val result = validator.validate("admin")
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.RESERVED_NAME, result.reason)
    }

    @Test
    fun `test safety blocklist remains blocking`() {
        val result = validator.validate("kill_user")
        assertFalse(result.isValid)
        assertTrue(result.hasBlockingError)
        assertEquals(ValidationReason.HATEFUL_LANGUAGE, result.reason)
    }

    @Test
    fun `test emotional tone filter is advisory and does not invalidate result`() {
        val result = validator.validate("depressed_soul")
        assertTrue("Advisory OVERLY_NEGATIVE warning should keep result valid", result.isValid)
        assertFalse(result.hasBlockingError)
        assertTrue(result.hasAdvisoryWarnings)
        assertEquals(ValidationReason.OVERLY_NEGATIVE, result.reason)
    }
}
