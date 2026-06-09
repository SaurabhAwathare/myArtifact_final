package com.saurabh.artifact.audio

import android.util.Log
import androidx.room.withTransaction
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

class DraftDeletionManagerTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val uploadTaskDao = mockk<UploadTaskDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val storageManager = mockk<StorageManager>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    private lateinit var deletionManager: DraftDeletionManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        // Try mocking WorkManager companion object
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns workManager
        
        deletionManager = DraftDeletionManager(
            draftDao = draftDao,
            uploadTaskDao = uploadTaskDao,
            draftsDatabase = database,
            storageManager = storageManager,
            workManager = workManager,
        )
        
        coEvery { draftDao.getDraftById(any()) } returns null
        coEvery { draftDao.updateStatus(any(), any(), any()) } returns Unit
        coEvery { draftDao.deleteById(any()) } returns Unit
        coEvery { uploadTaskDao.deleteByDraftId(any()) } returns Unit
        
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { 
            database.withTransaction<Any>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[1] as suspend () -> Any
            block.invoke()
        }
    }

    @Test
    fun `deleteDraft should update status to DELETING then delete files and then DB record`() = runBlocking {
        val draftId = "test-draft-id"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { status } returns mockk(relaxed = true) {
                every { lifecycle } returns ArtifactLifecycle.RECORDING
            }
            every { localAudioPath } returns "/path/audio.wav"
        }
        val draftDir = mockk<File>(relaxed = true) {
            every { absolutePath } returns "/path/dir"
        }

        coEvery { draftDao.getDraftById(draftId) } returns draft
        every { storageManager.getDraftDirectory(draftId) } returns draftDir
        every { storageManager.deleteDirectoryRecursively(any()) } returns true

        deletionManager.deleteDraft(draftId)

        coVerify {
            draftDao.markAsDeleting(draftId)
            storageManager.deleteDirectoryRecursively(draftDir)
            draftDao.deleteById(draftId)
        }
    }

    @Test
    fun `deleteDraft should enqueue retry if file deletion fails`() = runBlocking {
        val draftId = "test-draft-id"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { status } returns mockk(relaxed = true) {
                every { lifecycle } returns ArtifactLifecycle.RECORDING
            }
        }
        val draftDir = mockk<File>(relaxed = true)

        coEvery { draftDao.getDraftById(draftId) } returns draft
        every { storageManager.getDraftDirectory(draftId) } returns draftDir
        every { storageManager.deleteDirectoryRecursively(any()) } returns false

        deletionManager.deleteDraft(draftId)

        coVerify {
            workManager.enqueueUniqueWork("delete_$draftId", any(), any<OneTimeWorkRequest>())
        }
        coVerify(exactly = 0) {
            draftDao.deleteById(draftId)
        }
    }
}
