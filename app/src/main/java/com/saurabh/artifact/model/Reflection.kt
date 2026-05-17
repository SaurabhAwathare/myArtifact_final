package com.saurabh.artifact.model

data class ReflectionQuestion(
    val id: String,
    val text: String,
    val depthLevel: Int, // 1: Grounding, 2: Exploration, 3: Positive Shift, 4: Growth
    val variants: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)
