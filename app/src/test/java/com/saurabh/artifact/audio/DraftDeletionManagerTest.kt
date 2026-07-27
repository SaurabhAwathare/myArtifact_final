package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DraftDeletionManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val storageManager = mockk<StorageManager>(relaxed = true)
    private lateinit var deletionManager: DraftDeletionManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        
        deletionManager = DraftDeletionManager(
            storageManager = storageManager
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `performPhysicalPurge should delete draft directory and legacy files`() = runBlocking {
        val draftId = "test-draft-id"
        val draftDir = tempFolder.newFolder("draft_$draftId")
        
        // Create a legacy file
        val legacyDir = tempFolder.newFolder("legacy")
        val legacyFile = File(legacyDir, "audio.wav").apply { createNewFile() }
        
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { localAudioPath } returns legacyFile.absolutePath
        }

        every { storageManager.getDraftDirectory(draftId) } returns draftDir

        deletionManager.performPhysicalPurge(draft)

        verify {
            storageManager.deleteDirectoryRecursively(draftDir)
            storageManager.deleteSecurely(match { it.absolutePath == legacyFile.absolutePath })
        }
    }
}
