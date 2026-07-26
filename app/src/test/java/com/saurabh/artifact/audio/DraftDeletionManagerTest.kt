package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

class DraftDeletionManagerTest {
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

    @Test
    fun `performPhysicalPurge should delete draft directory and legacy files`() = runBlocking {
        val draftId = "test-draft-id"
        val draftDir = mockk<File>(relaxed = true) {
            every { absolutePath } returns "/path/dir"
        }
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { localAudioPath } returns "/legacy/audio.wav"
        }

        every { storageManager.getDraftDirectory(draftId) } returns draftDir
        mockkConstructor(File::class)
        every { anyConstructed<File>().exists() } returns true
        every { anyConstructed<File>().absolutePath } returns "/legacy/audio.wav"

        deletionManager.performPhysicalPurge(draft)

        verify {
            storageManager.deleteDirectoryRecursively(draftDir)
        }
    }
}
