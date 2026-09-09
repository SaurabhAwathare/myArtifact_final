package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ValidationReason
import org.junit.Assert.*
import org.junit.Test

class UsernameValidatorTest {

    private val validator = UsernameValidator(IdentityScout())

    @Test
    fun `test valid usernames`() {
        val result = validator.validate("quiet_river_42")
        assertTrue(result.isValid)
        assertNull(result.reason)
    }

    @Test
    fun `test generated username containing space is accepted`() {
        val result = validator.validate("Quiet Path")
        assertTrue(result.isValid)
        assertNull(result.reason)
    }

    @Test
    fun `test generated username containing supported middle dot character is handled consistently`() {
        val result = validator.validate("Quiet Path · A7")
        assertTrue(result.isValid)
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
        assertEquals(ValidationReason.TOO_LONG, result31.reason)
    }

    @Test
    fun `test too short username`() {
        val result = validator.validate("hi")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.TOO_SHORT, result.reason)
    }

    @Test
    fun `test invalid characters`() {
        val result = validator.validate("invalid_name!")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.INVALID_CHARACTERS, result.reason)
    }

    @Test
    fun `test email detection`() {
        val result = validator.validate("user@gmail.com")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.EMAIL_ADDRESS, result.reason)
    }

    @Test
    fun `test phone number detection`() {
        val result = validator.validate("user9876543210")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.PHONE_NUMBER, result.reason)
    }

    @Test
    fun `test reserved name`() {
        val result = validator.validate("admin")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.RESERVED_NAME, result.reason)
    }

    @Test
    fun `test safety blocklist`() {
        val result = validator.validate("kill_user")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.HATEFUL_LANGUAGE, result.reason)
    }

    @Test
    fun `test emotional tone filter`() {
        val result = validator.validate("depressed_soul")
        assertFalse(result.isValid)
        assertEquals(ValidationReason.OVERLY_NEGATIVE, result.reason)
    }
}
