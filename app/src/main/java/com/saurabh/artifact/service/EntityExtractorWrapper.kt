package com.saurabh.artifact.service

import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

interface EntityExtractorWrapper {
    suspend fun isModelAvailable(): Boolean
    suspend fun annotate(text: String): List<EntityAnnotation>
}

@Singleton
class MlKitEntityExtractorWrapper @Inject constructor() : EntityExtractorWrapper {

    private val entityExtractor by lazy {
        EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        )
    }

    override suspend fun isModelAvailable(): Boolean {
        return try {
            entityExtractor.downloadModelIfNeeded().await()
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun annotate(text: String): List<EntityAnnotation> {
        val params = EntityExtractionParams.Builder(text).build()
        return entityExtractor.annotate(params).await()
    }
}
