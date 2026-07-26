package com.saurabh.artifact.repository

import androidx.media3.common.util.UnstableApi
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.service.PersonalizationEngine
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Before
import org.junit.Test

@OptIn(UnstableApi::class)
class ArtifactEngagementRepositoryTest {
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val personalizationEngine = mockk<PersonalizationEngine>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: ArtifactEngagementRepository

    @Before
    fun setup() {
        repository = ArtifactEngagementRepository(
            firestore = firestore,
            personalizationEngine = { personalizationEngine },
            settingsRepository = { settingsRepository },
            diagnosticLogger = diagnosticLogger
        )
    }

    @Test
    fun `recordPlay should record interaction locally if consent is given`() = runBlocking {
        val emotion = "Joy"
        val userId = "user123"
        val artifactId = "art123"
        
        every { settingsRepository.userSettings } returns flowOf(UserSettings(dataCollectionConsent = true))
        
        repository.recordPlay(userId, artifactId, emotion)
        
        verify { personalizationEngine.recordInteraction(emotion) }
    }

    @Test
    fun `recordPlay should not record interaction locally if consent is denied`() = runBlocking {
        val emotion = "Joy"
        val userId = "user123"
        val artifactId = "art123"
        
        every { settingsRepository.userSettings } returns flowOf(UserSettings(dataCollectionConsent = false))
        
        repository.recordPlay(userId, artifactId, emotion)
        
        verify(exactly = 0) { personalizationEngine.recordInteraction(emotion) }
    }

    @Test
    fun `submitPrivateFeedback should trigger local re-ranking for NOT_FOR_ME if consent is given`() = runBlocking {
        val artifactId = "art123"
        val userId = "user123"
        val emotion = "Joy"
        
        every { settingsRepository.userSettings } returns flowOf(UserSettings(dataCollectionConsent = true))
        
        val doc = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        every { doc.getString("emotion") } returns emotion
        
        val task = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.DocumentSnapshot>>(relaxed = true)
        every { firestore.collection("artifacts").document(artifactId).get() } returns task
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task.await() } returns doc

        repository.submitPrivateFeedback(artifactId, userId, FeedbackType.NOT_FOR_ME)
        
        verify { personalizationEngine.recordInteraction(emotion, weight = -1.0f) }
    }
}
