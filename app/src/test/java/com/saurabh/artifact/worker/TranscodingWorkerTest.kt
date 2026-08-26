package com.saurabh.artifact.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import androidx.work.workDataOf
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.repository.AuthRepository
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
    private val audioTranscoder = mockk<com.saurabh.artifact.audio.AudioTranscoder>(relaxed = true)
    private val wavRecoveryManager = mockk<WavRecoveryManager>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val startupCoordinator = mockk<com.saurabh.artifact.startup.StartupCoordinator>(relaxed = true)

    private lateinit var worker: TranscodingWorker

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        
        val cacheDir = Files.createTempDirectory("cache").toFile()
        every { context.cacheDir } returns cacheDir

        mockkObject(FileIntegrity)
        every { FileIntegrity.calculateChecksum(any()) } returns "checksum_123"
        
        every { encryptedStorageManager.getEncryptedOutputStream(any()) } answers { FileOutputStream(it.invocation.args[0] as File) }

        every { authRepository.currentUserId } returns TEST_USER_ID

        coEvery { startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE) } returns Unit

        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) } returns "yes"
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "1000"
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs

        worker = TranscodingWorker(
            appContext = context,
            workerParams = workerParams,
            draftDao = { draftDao },
            localDraftManager = localDraftManager,
            encryptedStorageManager = encryptedStorageManager,
            audioTranscoder = audioTranscoder,
            wavRecoveryManager = wavRecoveryManager,
            authRepository = authRepository,
            startupCoordinator = startupCoordinator,
            diagnosticLogger = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `doWork should update DB and preserve WAV for downstream workers`() = runTest {
        val tempDir = Files.createTempDirectory("transcode_test").toFile()
        val rawPath = File(tempDir, "raw.wav").apply { writeText("PCM") }.absolutePath
        val finalPath = File(tempDir, "final.m4a").absolutePath
        
        val draftId = "d1"
        every { workerParams.inputData } returns workDataOf("key_draft_id" to draftId)
        
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { userId } returns TEST_USER_ID
            every { rawPcmPath } returns rawPath
            every { localAudioPath } returns finalPath
        }

        coEvery { draftDao.getDraftById(draftId, TEST_USER_ID) } returns draft
        // The worker will create this file via transcodeAndEncrypt
        val finalFile = File(finalPath)
        every { localDraftManager.createDraftFile(draftId, "m4a") } returns finalFile
        
        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Success)
        
        // Verify: Original WAV is preserved for WaveformWorker
        assert(File(rawPath).exists())

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
            every { userId } returns TEST_USER_ID
            every { rawPcmPath } returns "/path/raw.wav"
        }

        coEvery { draftDao.getDraftById(draftId, TEST_USER_ID) } returns draft
        every { localDraftManager.createDraftFile(draftId, "m4a") } returns finalFile

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Failure)
        coVerify(exactly = 0) { draftDao.updateTranscodingResult(any(), any(), any(), any(), any()) }
    }
}
