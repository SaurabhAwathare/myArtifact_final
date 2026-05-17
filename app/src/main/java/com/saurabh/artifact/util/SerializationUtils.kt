package com.saurabh.artifact.util

import com.google.firebase.Timestamp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for Firebase Timestamp to work with kotlinx.serialization.
 */
object TimestampSerializer : KSerializer<Timestamp> {
    @Serializable
    private data class TimestampSurrogate(val seconds: Long, val nanoseconds: Int)

    override val descriptor: SerialDescriptor = TimestampSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Timestamp) {
        val surrogate = TimestampSurrogate(value.seconds, value.nanoseconds)
        encoder.encodeSerializableValue(TimestampSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): Timestamp {
        val surrogate = decoder.decodeSerializableValue(TimestampSurrogate.serializer())
        return Timestamp(surrogate.seconds, surrogate.nanoseconds)
    }
}
