package com.saurabh.artifact.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionTypeTest {

    @Test
    fun `fromId should map lowercase IDs correctly`() {
        assertEquals(ReactionType.I_HEAR_YOU, ReactionType.fromId("i_hear_you"))
        assertEquals(ReactionType.SENDING_STRENGTH, ReactionType.fromId("sending_strength"))
    }

    @Test
    fun `fromId should map uppercase Names correctly`() {
        assertEquals(ReactionType.I_HEAR_YOU, ReactionType.fromId("I_HEAR_YOU"))
        assertEquals(ReactionType.SENDING_STRENGTH, ReactionType.fromId("SENDING_STRENGTH"))
    }

    @Test
    fun `fromId should handle mixed case IDs correctly`() {
        assertEquals(ReactionType.I_HEAR_YOU, ReactionType.fromId("I_hear_You"))
    }

    @Test
    fun `fromId should return default for unknown IDs`() {
        assertEquals(ReactionType.I_HEAR_YOU, ReactionType.fromId("unknown_reaction"))
    }
}
