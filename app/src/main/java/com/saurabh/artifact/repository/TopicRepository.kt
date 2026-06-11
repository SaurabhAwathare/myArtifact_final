package com.saurabh.artifact.repository

import com.saurabh.artifact.model.TopicTag
import com.saurabh.artifact.model.TopicCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface TopicRepository {
    fun getSystemTopics(): Flow<List<TopicTag>>
    fun getCategories(): Flow<List<TopicCategory>>
    suspend fun searchTopics(query: String): List<TopicTag>
    suspend fun saveCustomTopic(topic: TopicTag): Result<Unit>
}

@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val debugRepository: DebugRepository
) : TopicRepository {
    // In a real implementation, this would inject Firestore
    
    private val mockTopics = listOf(
        TopicTag(id = "1", label = "burnout"),
        TopicTag(id = "2", label = "loneliness"),
        TopicTag(id = "3", label = "self-worth"),
        TopicTag(id = "4", label = "relationships"),
        TopicTag(id = "5", label = "college life"),
        TopicTag(id = "6", label = "grief"),
        TopicTag(id = "7", label = "creative burnout"),
        TopicTag(id = "8", label = "anxiety"),
        TopicTag(id = "9", label = "family"),
        TopicTag(id = "10", label = "hope"),
        TopicTag(id = "11", label = "loss")
    )

    override fun getSystemTopics(): Flow<List<TopicTag>> = debugRepository.debugSettings.map { settings ->
        if (settings.useMockTopics) mockTopics else emptyList()
    }

    override fun getCategories(): Flow<List<TopicCategory>> = flowOf(emptyList())

    override suspend fun searchTopics(query: String): List<TopicTag> {
        val useMock = debugRepository.debugSettings.map { it.useMockTopics }.first()
        if (!useMock) return emptyList()
        return mockTopics.filter { it.label.contains(query, ignoreCase = true) }
    }

    override suspend fun saveCustomTopic(topic: TopicTag): Result<Unit> {
        // Save to Firestore
        return Result.success(Unit)
    }
}
