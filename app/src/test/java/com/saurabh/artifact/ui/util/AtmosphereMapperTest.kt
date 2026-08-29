package com.saurabh.artifact.ui.util

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.CommunityAtmosphere
import org.junit.Assert.assertEquals
import org.junit.Test

class AtmosphereMapperTest {

    @Test
    fun `mapToStatement returns default when insufficient data`() {
        val atmosphere = CommunityAtmosphere(status = "INSUFFICIENT_DATA", totalArtifacts = 5)
        val statement = AtmosphereMapper.mapToStatement(atmosphere)
        assertEquals("The Human Library is resonating quietly today.", statement)
    }

    @Test
    fun `mapToStatement returns diversity default when no category reaches threshold`() {
        // Total 20. Threshold 15% is 3. 
        // All categories have 2, so none reach threshold.
        val counts = mapOf(
            "Happy" to 2L,
            "Sad" to 2L,
            "Anxious" to 2L,
            "Neutral" to 2L,
            "Motivated" to 2L,
            "Lonely" to 2L,
            "Mixed" to 2L,
            "Grateful" to 2L
        )
        val atmosphere = CommunityAtmosphere(
            totalArtifacts = 20,
            categoryCounts = counts,
            status = "ACTIVE"
        )
        val statement = AtmosphereMapper.mapToStatement(atmosphere)
        assertEquals("The Human Library is carrying many different feelings today.", statement)
    }

    @Test
    fun `mapToStatement selects dominant category when alone above threshold`() {
        val counts = mapOf(
            "Happy" to 10L, // 50%
            "Sad" to 1L
        )
        val atmosphere = CommunityAtmosphere(
            totalArtifacts = 20,
            categoryCounts = counts,
            status = "ACTIVE"
        )
        val statement = AtmosphereMapper.mapToStatement(atmosphere)
        assertEquals("Today, many people are reflecting on Hope.", statement)
    }

    @Test
    fun `mapToStatement handles rotation based on generatedAt`() {
        val counts = mapOf(
            "Happy" to 10L,
            "Sad" to 10L
        )
        val total = 20L
        
        // If generatedAt.seconds % 2 == 0 -> "Happy"
        val atmosphere0 = CommunityAtmosphere(
            generatedAt = Timestamp(1000, 0),
            totalArtifacts = total,
            categoryCounts = counts,
            status = "ACTIVE"
        )
        assertEquals("Today, many people are reflecting on Hope.", AtmosphereMapper.mapToStatement(atmosphere0))

        // If generatedAt.seconds % 2 == 1 -> "Sad"
        val atmosphere1 = CommunityAtmosphere(
            generatedAt = Timestamp(1001, 0),
            totalArtifacts = total,
            categoryCounts = counts,
            status = "ACTIVE"
        )
        assertEquals("The community is holding space for grief and reflection today.", AtmosphereMapper.mapToStatement(atmosphere1))
    }
}
