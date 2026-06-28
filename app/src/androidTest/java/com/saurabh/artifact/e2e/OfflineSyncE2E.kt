package com.saurabh.artifact.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.ArtifactRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OfflineSyncE2E {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var artifactRepository: ArtifactRepository

    @Inject
    lateinit var pendingInteractionDao: PendingInteractionDao

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun testOfflineQueueingFlow() = runBlocking {
        val artifact = Artifact(id = "art_offline_test", userId = "owner1", title = "Offline Test")
        val userId = "user123"

        // Perform Save interaction (this will enqueue locally)
        val result = artifactRepository.saveArtifact(userId, artifact)
        assertTrue(result.isSuccess)

        // Verify the interaction is persisted in the local database
        val pending = pendingInteractionDao.getPendingForUser(userId)
        assertTrue("Interaction should be in the pending queue", 
            pending.any { it.artifactId == "art_offline_test" && it.interactionType == com.saurabh.artifact.data.local.InteractionType.SAVE })
    }
}
