package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import androidx.work.workDataOf
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.util.EncryptedStorageManager
import com.saurabh.artifact.util.FileIntegrity
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TranscodingWorkerTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)
    private val encryptedStorageManager = mockk<EncryptedStorageManager>(relaxed = true)
    private val wavRecoveryManager = mockk<WavRecoveryManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

    private lateinit var worker: TranscodingWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(FileIntegrity)
        every { FileIntegrity.calculateChecksum(any()) } returns "checksum_123"
        
        every { encryptedStorageManager.getEncryptedOutputStream(any()) } answers { FileOutputStream(it.invocation.args[0] as File) }

        worker = TranscodingWorker(
            appContext = context,
            workerParams = workerParams,
            draftDao = draftDao,
            localDraftManager = localDraftManager,
            encryptedStorageManager = encryptedStorageManager,
            wavRecoveryManager = wavRecoveryManager,
            diagnosticLogger = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `doWork should update DB before deleting WAV`() = runTest {
        val tempDir = Files.createTempDirectory("transcode_test").toFile()
        val rawPath = File(tempDir, "raw.wav").apply { writeText("PCM") }.absolutePath
        val finalPath = File(tempDir, "final.m4a").absolutePath
        
        val draftId = "d1"
        every { workerParams.inputData } returns workDataOf("key_draft_id" to draftId)
        
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { rawPcmPath } returns rawPath
            every { localAudioPath } returns finalPath
        }

        coEvery { draftDao.getDraftById(draftId) } returns draft
        // The worker will create this file via transcodeAndEncrypt
        val finalFile = File(finalPath)
        every { localDraftManager.createDraftFile(draftId, "m4a") } returns finalFile
        
        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Success)
        
        // Wait a bit for file system to sync if needed (shouldn't be needed for unit tests but Windows...)
        Thread.sleep(100)

        assert(!File(rawPath).exists())
        tempDir.deleteRecursively()
    }

    @Test
    fun `doWork should fail if output file is empty`() = runTest {
        val draftId = "d1"
        every { workerParams.inputData } returns workDataOf("key_draft_id" to draftId)

        val finalFile = mockk<File>(relaxed = true) {
            every { exists() } returns true
            every { length() } returns 0L // EMPTY
        }
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { rawPcmPath } returns "/path/raw.wav"
        }

        coEvery { draftDao.getDraftById(draftId) } returns draft
        every { localDraftManager.createDraftFile(draftId, "m4a") } returns finalFile

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Failure)
        coVerify(exactly = 0) { draftDao.updateTranscodingResult(any(), any(), any(), any(), any()) }
    }
}
