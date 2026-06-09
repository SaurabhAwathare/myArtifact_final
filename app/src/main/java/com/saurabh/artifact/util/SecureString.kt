package com.saurabh.artifact.util

import java.util.Arrays
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A wrapper around a [CharArray] that allows for explicit clearing of sensitive data.
 */
@Serializable(with = SecureStringSerializer::class)
class SecureString(private val chars: CharArray) : CharSequence {

    override val length: Int get() = chars.size

    override fun get(index: Int): Char = chars[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        return SecureString(chars.copyOfRange(startIndex, endIndex))
    }

    /**
     * Clears the underlying character array by filling it with null characters.
     * This should be called as soon as the sensitive data is no longer needed.
     */
    fun clear() {
        Arrays.fill(chars, '\u0000')
    }

    /**
     * Converts the secure content to a standard [String].
     * WARNING: This creates an immutable copy in the heap that cannot be cleared.
     * Use this only at the boundaries (e.g., UI display or API calls).
     */
    fun toUnsecureString(): String = String(chars)

    override fun toString(): String {
        return "SecureString(length=$length)"
    }

    companion object {
        fun fromString(str: String): SecureString {
            return SecureString(str.toCharArray())
        }
        
        fun empty(): SecureString = SecureString(charArrayOf())
    }
}

object SecureStringSerializer : KSerializer<SecureString> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SecureString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SecureString) {
        encoder.encodeString(value.toUnsecureString())
    }

    override fun deserialize(decoder: Decoder): SecureString {
        return SecureString.fromString(decoder.decodeString())
    }
}
