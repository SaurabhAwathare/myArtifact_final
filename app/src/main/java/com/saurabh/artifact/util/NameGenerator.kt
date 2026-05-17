package com.saurabh.artifact.util

object NameGenerator {
    private val adjectives = listOf(
        "Silver", "Quiet", "Ancient", "Radiant", "Hidden", "Vibrant",
        "Ethereal", "Nomadic", "Serene", "Melodic", "Luminous", "Primal"
    )
    private val artifacts = listOf(
        "Echo", "Relic", "Trace", "Compass", "Vessel", "Lens",
        "Orb", "Prism", "Shard", "Spire", "Well", "Path"
    )

    fun generate(): String {
        return "${adjectives.random()} ${artifacts.random()}"
    }
}
