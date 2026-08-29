package com.saurabh.artifact.security

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.android.gms.tasks.Tasks
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.ArtifactLibraryRepository
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.util.EncryptedStorageManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class DataExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val draftDao = mockk<DraftDao>()
    private val authRepository = mockk<AuthRepository>()
    private val artifactRepository = mockk<ArtifactRepository>()
    private val userRepository = mockk<UserRepository>()
    private val libraryRepository = mockk<ArtifactLibraryRepository>()
    private val encryptedStorageManager = mockk<EncryptedStorageManager>()
    private val firestore = mockk<FirebaseFirestore>()
    private val storage = mockk<FirebaseStorage>()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>()

    private val mockDoc = mockk<DocumentReference>(relaxed = true)
    private val mockColl = mockk<CollectionReference>(relaxed = true)
    private val mockQuery = mockk<Query>(relaxed = true)
    private val mockTask = Tasks.forResult(mockk<QuerySnapshot>(relaxed = true))

    private lateinit var dataExportManager: DataExportManager

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    @Before
    fun setup() {
        every { context.contentResolver } returns contentResolver
        
        // Provide consistent mocks to avoid hangs
        every { mockQuery.get() } returns mockTask
        every { mockQuery.whereEqualTo(any<String>(), any()) } returns mockQuery
        
        every { mockColl.get() } returns mockTask
        every { mockColl.whereEqualTo(any<String>(), any()) } returns mockQuery
        every { mockColl.document(any()) } returns mockDoc
        
        every { mockDoc.collection(any()) } returns mockColl
        
        every { firestore.collection(any()) } returns mockColl
        every { firestore.collectionGroup(any()) } returns mockQuery

        dataExportManager = DataExportManager(
            context,
            { draftDao },
            authRepository,
            { artifactRepository },
            { userRepository },
            { libraryRepository },
            encryptedStorageManager,
            firestore,
            storage,
            diagnosticLogger
        )
    }

    @Test
    fun `test export produces valid plain ZIP with expected structure`() = runBlocking {
        // 1. Setup mocks
        val userId = TEST_USER_ID
        every { authRepository.currentUserId } returns userId
        
        coEvery { userRepository.getCachedProfile(userId) } returns User(id = userId, anonymousName = "Test User")
        coEvery { artifactRepository.getUserArtifactsPage(userId, any()) } returns Result.success(emptyList<Artifact>() to null)
        coEvery { draftDao.getAllDraftsByUserId(userId) } returns listOf(
            ArtifactDraftEntity(
                id = "draft_1",
                userId = userId,
                localAudioPath = tempFolder.newFile("audio.wav").absolutePath,
                lifecycle = ArtifactLifecycle.READY_TO_PUBLISH
            )
        )
        // Participation & Relationships - Simplified mocks
        every { userRepository.observeResonatingWithIds(userId) } returns flowOf(emptySet())
        every { libraryRepository.getSavedArtifactIds(userId) } returns flowOf(emptySet())

        val outputFile = tempFolder.newFile("export.zip")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        // 2. Execute Export
        val result = dataExportManager.exportData(uri)
        assertTrue(result.isSuccess)

        // 3. Verify the file is a plain ZIP and contains manifest
        var hasManifest = false
        var hasReadme = false
        var hasDraft = false
        
        ZipInputStream(outputFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") hasManifest = true
                if (entry.name == "README.txt") hasReadme = true
                if (entry.name.contains("Drafts/draft_")) hasDraft = true
                entry = zipIn.nextEntry
            }
        }
        
        assertTrue("Should contain manifest.json", hasManifest)
        assertTrue("Should contain README.txt", hasReadme)
        assertTrue("Should contain drafts", hasDraft)
    }

    @Test
    fun `test export emits progress updates`() = runBlocking {
        val userId = TEST_USER_ID
        every { authRepository.currentUserId } returns userId
        coEvery { userRepository.getCachedProfile(userId) } returns User(id = userId, anonymousName = "Test User")
        coEvery { artifactRepository.getUserArtifactsPage(userId, any()) } returns Result.success(emptyList<Artifact>() to null)
        coEvery { draftDao.getAllDraftsByUserId(userId) } returns emptyList()
        every { userRepository.observeResonatingWithIds(userId) } returns flowOf(emptySet())
        every { libraryRepository.getSavedArtifactIds(userId) } returns flowOf(emptySet())

        val outputFile = tempFolder.newFile("progress_test.zip")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        val progressUpdates = mutableListOf<ExportProgress>()
        dataExportManager.exportData(uri) { progressUpdates.add(it) }

        assertTrue("Should emit Starting", progressUpdates.contains(ExportProgress.Starting))
        assertTrue("Should emit Profile", progressUpdates.contains(ExportProgress.Profile))
        assertTrue("Should emit Finalizing", progressUpdates.contains(ExportProgress.Finalizing))
        assertTrue("Should emit Complete", progressUpdates.any { it is ExportProgress.Complete })
    }

    @Test
    fun `test export isolation - Stay With Me does not export other user audio`() = runBlocking {
        val userId = TEST_USER_ID
        val otherArtifactId = "other_artifact_123"
        
        every { authRepository.currentUserId } returns userId
        coEvery { userRepository.getCachedProfile(userId) } returns User(id = userId, anonymousName = "Test User")
        coEvery { artifactRepository.getUserArtifactsPage(userId, any()) } returns Result.success(emptyList<Artifact>() to null)
        coEvery { draftDao.getAllDraftsByUserId(userId) } returns emptyList()
        
        // Mock "Stay With Me" references
        every { libraryRepository.getSavedArtifactIds(userId) } returns flowOf(setOf(otherArtifactId))
        
        // Other mocks
        every { userRepository.observeResonatingWithIds(userId) } returns flowOf(emptySet())

        val outputFile = tempFolder.newFile("isolation_stay_with_me.zip")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        // Execute Export
        dataExportManager.exportData(uri)

        // Verify ZIP content
        ZipInputStream(outputFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                // Ensure no entry belongs to the other artifact ID (especially no audio)
                assertFalse("Export must NOT contain other user's artifact audio or metadata", 
                    entry.name.contains(otherArtifactId) && !entry.name.contains("stayed_with_me.json"))
                entry = zipIn.nextEntry
            }
        }
    }

    @Test
    fun `test export isolation - only current user data included`() = runBlocking {
        every { authRepository.currentUserId } returns ""
        
        val uri = mockk<Uri>()
        val result = dataExportManager.exportData(uri)
        
        assertFalse("Export should fail if unauthenticated", result.isSuccess)
        assertEquals("Unauthenticated export attempt", result.exceptionOrNull()?.message)
    }

    @Test
    fun `test export isolation - account switch during export causes abortion`() = runBlocking {
        val userId = TEST_USER_ID
        val otherUserId = "other-user-id"
        
        // Initial state
        every { authRepository.currentUserId } returns userId
        
        // Mock output stream
        val outputFile = tempFolder.newFile("switch_test.zip")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        // Mock the first phase success, but trigger a switch before the second phase
        coEvery { userRepository.getCachedProfile(userId) } coAnswers {
            // Simulate account switch
            every { authRepository.currentUserId } returns otherUserId
            User(id = userId, anonymousName = "User A")
        }
        
        // Participation & Relationships - Simplified mocks
        every { userRepository.observeResonatingWithIds(userId) } returns flowOf(emptySet())
        every { libraryRepository.getSavedArtifactIds(userId) } returns flowOf(emptySet())

        // Execute Export
        val result = dataExportManager.exportData(uri)

        assertFalse("Export should fail if account switched", result.isSuccess)
        assertTrue("Error should mention account switch", result.exceptionOrNull()?.message?.contains("Account switch detected") == true)
    }

    @Test
    fun `test export produces complete participation and safety history`() = runBlocking {
        val userId = TEST_USER_ID
        every { authRepository.currentUserId } returns userId
        coEvery { userRepository.getCachedProfile(userId) } returns User(id = userId, anonymousName = "Test User")
        coEvery { artifactRepository.getUserArtifactsPage(userId, any()) } returns Result.success(emptyList<Artifact>() to null)
        coEvery { draftDao.getAllDraftsByUserId(userId) } returns emptyList()

        // Mock Engagement
        val engagementColl = mockk<CollectionReference>(relaxed = true)
        val engagementSnapshot = mockk<QuerySnapshot>(relaxed = true)
        val engagementDoc = mockk<com.google.firebase.firestore.QueryDocumentSnapshot>(relaxed = true)
        
        every { firestore.collection("users").document(userId).collection("engagement") } returns engagementColl
        every { engagementColl.get() } returns Tasks.forResult(engagementSnapshot)
        
        // Mock Reports
        val reportsColl = mockk<CollectionReference>(relaxed = true)
        val reportsQuery = mockk<Query>(relaxed = true)
        val reportsSnapshot = mockk<QuerySnapshot>(relaxed = true)
        val reportDoc = mockk<com.google.firebase.firestore.QueryDocumentSnapshot>(relaxed = true)
        
        every { firestore.collection("reports") } returns reportsColl
        every { reportsColl.whereEqualTo("reporterId", userId) } returns reportsQuery
        every { reportsQuery.get() } returns Tasks.forResult(reportsSnapshot)
        
        every { engagementSnapshot.documents } returns listOf(engagementDoc)
        every { engagementDoc.id } returns "art_1"
        every { engagementDoc.getString("artifactId") } returns "art_1"
        every { engagementDoc.getBoolean("isCommentUnlocked") } returns true
        every { engagementDoc.getString("engagementState") } returns "UNLOCKED"

        every { reportsSnapshot.documents } returns listOf(reportDoc)
        every { reportDoc.getString("artifactId") } returns "art_bad"
        every { reportDoc.getString("reason") } returns "HARASSMENT"
        every { reportDoc.getString("status") } returns "RESOLVED"

        // Other mocks
        every { userRepository.observeResonatingWithIds(userId) } returns flowOf(emptySet())
        every { libraryRepository.getSavedArtifactIds(userId) } returns flowOf(emptySet())

        val outputFile = tempFolder.newFile("completeness_test.zip")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        // Execute Export
        val result = dataExportManager.exportData(uri)
        assertTrue(result.isSuccess)

        // Verify ZIP content
        var hasEngagement = false
        var hasReports = false
        
        ZipInputStream(outputFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (entry.name == "Participation/engagement_history.json") hasEngagement = true
                if (entry.name == "Safety/reports_authored.json") hasReports = true
                entry = zipIn.nextEntry
            }
        }
        
        assertTrue("Should contain engagement history", hasEngagement)
        assertTrue("Should contain authored reports", hasReports)
    }
}
