package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.util.ConnectivityObserver
import com.saurabh.artifact.util.EncryptedStorageManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

import dagger.Lazy

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSyncWorkerTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val encryptedStorageManager = mockk<EncryptedStorageManager>(relaxed = true)
    private val backupEncryptionManager = mockk<BackupEncryptionManager>(relaxed = true)
    private val storage = mockk<FirebaseStorage>(relaxed = true)
    private val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

    private lateinit var worker: BackupSyncWorker

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        worker = BackupSyncWorker(
            context,
            workerParams,
            Lazy { draftDao },
            authRepository,
            encryptedStorageManager,
            backupEncryptionManager,
            storage,
            connectivityObserver,
            diagnosticLogger
        )
        
        every { connectivityObserver.isOnline() } returns true
        every { authRepository.currentUser.value?.uid } returns "user_1"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `doWork should skip DELETING and DELETED drafts`() = runTest {
        val deletingDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "d1"
            every { lifecycle } returns ArtifactLifecycle.DELETING
            every { status.backup } returns SyncStatus.LocalOnly
        }
        val deletedDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "d2"
            every { lifecycle } returns ArtifactLifecycle.DELETED
            every { status.backup } returns SyncStatus.LocalOnly
        }
        val readyDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "d3"
            every { lifecycle } returns ArtifactLifecycle.REVIEW_REQUIRED
            every { status.backup } returns SyncStatus.LocalOnly
            every { localAudioPath } returns "/fake/path"
            every { isEncrypted } returns false
        }

        coEvery { draftDao.getAllDraftsByUserId("user_1") } returns listOf(deletingDraft, deletedDraft, readyDraft)
        
        // Mock success for the ready draft to avoid exceptions
        coEvery { backupEncryptionManager.encryptForBackup(any()) } returns byteArrayOf(1, 2, 3)
        val storageRef = mockk<com.google.firebase.storage.StorageReference>(relaxed = true)
        every { storage.reference.child(any()) } returns storageRef
        val uploadTask = mockk<com.google.firebase.storage.UploadTask>(relaxed = true)
        every { storageRef.putBytes(any()) } returns uploadTask
        coEvery { uploadTask.await() } returns mockk()

        worker.doWork()

        // Verify that updateStatus was NOT called for d1 and d2
        coVerify(exactly = 0) { draftDao.updateStatus("d1", any(), any(), any()) }
        coVerify(exactly = 0) { draftDao.updateStatus("d2", any(), any(), any()) }
        
        // Verify it was called for d3
        coVerify(atLeast = 1) { draftDao.updateStatus("d3", any(), any(), any()) }
    }
}
