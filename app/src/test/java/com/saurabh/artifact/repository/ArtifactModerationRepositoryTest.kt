package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.saurabh.artifact.data.local.ReportedArtifactDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.ReportReason
import com.saurabh.artifact.model.ReportStatus
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.ModerationStatus
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtifactModerationRepositoryTest {
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val functions = mockk<FirebaseFunctions>(relaxed = true)
    private val reportedArtifactDao = mockk<ReportedArtifactDao>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: ArtifactModerationRepository

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        repository = ArtifactModerationRepository(
            auth = auth,
            firestore = firestore,
            functions = functions,
            reportedArtifactDao = { reportedArtifactDao },
            diagnosticLogger = diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `submitReport should create deterministic document and update local ReportedArtifactDao`() = runBlocking {
        val artifactId = "art123"
        val userId = "user123"
        val reason = ReportReason.HARASSMENT
        val details = "Some description"
        val deviceId = 456
        
        every { auth.currentUser?.uid } returns userId
        
        val reportRef = mockk<DocumentReference>(relaxed = true)
        val expectedReportId = "${userId}_${artifactId}"
        every { firestore.collection("reports").document(expectedReportId) } returns reportRef
        
        val setTask = mockk<Task<Void>>(relaxed = true)
        every { reportRef.set(any()) } returns setTask
        
        coEvery { setTask.await() } returns mockk(relaxed = true)

        val result = repository.submitReport(artifactId, reason, details, deviceId)

        assertTrue(result.isSuccess)
        
        // Verify Firestore write
        verify { reportRef.set(match { data ->
            val dataMap = data as Map<String, Any?>
            dataMap["artifactId"] as? String == artifactId &&
            dataMap["reporterId"] as? String == userId &&
            dataMap["reason"] as? String == reason.name &&
            dataMap["optionalDescription"] as? String == details &&
            dataMap["deviceIdHash"] as? Int == deviceId &&
            dataMap["status"] as? String == ReportStatus.PENDING.name
        }) }
        
        // Verify local Room update
        coVerify { reportedArtifactDao.insert(match { 
            it.userId == userId && it.artifactId == artifactId 
        }) }
    }

    @Test
    fun `submitReport with CHILD_SAFETY should create correct Firestore document`() = runBlocking {
        val artifactId = "art_csam"
        val userId = "user123"
        val reason = ReportReason.CHILD_SAFETY
        val details = "Urgent: CSAM"
        val deviceId = 789
        
        every { auth.currentUser?.uid } returns userId
        
        val reportRef = mockk<DocumentReference>(relaxed = true)
        val expectedReportId = "${userId}_${artifactId}"
        every { firestore.collection("reports").document(expectedReportId) } returns reportRef
        
        val setTask = mockk<Task<Void>>(relaxed = true)
        every { reportRef.set(any()) } returns setTask
        
        coEvery { setTask.await() } returns mockk(relaxed = true)

        val result = repository.submitReport(artifactId, reason, details, deviceId)

        assertTrue(result.isSuccess)
        
        verify { reportRef.set(match { data ->
            val dataMap = data as Map<String, Any?>
            dataMap["reason"] == "CHILD_SAFETY"
        }) }
    }

    @Test
    fun `resolveReport with HIDE_ARTIFACT should update report and artifact status`() = runBlocking {
        val reportId = "report123"
        val artifactId = "art123"
        
        val reportRef = mockk<DocumentReference>(relaxed = true)
        val artifactRef = mockk<DocumentReference>(relaxed = true)
        
        every { firestore.collection("reports").document(reportId) } returns reportRef
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        // Mock Transaction
        val transaction = mockk<Transaction>(relaxed = true)
        val transactionTask = mockk<Task<Unit>>(relaxed = true)
        
        val transactionSlot = slot<Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(transactionSlot)) } returns transactionTask
        
        coEvery { transactionTask.await() } answers {
            transactionSlot.captured.apply(transaction)
            Unit
        }

        val result = repository.resolveReport(reportId, artifactId, ArtifactRepository.ModerationAction.HIDE_ARTIFACT)

        assertTrue(result.isSuccess)
        verify { transaction.update(reportRef, "status", ReportStatus.RESOLVED.name) }
        verify { transaction.update(artifactRef, "moderation.status", ModerationStatus.HIDDEN.name) }
        verify { transaction.update(artifactRef, "isPublic", false) }
    }

    @Test
    fun `resolveReport with PLACE_ON_LEGAL_HOLD should update artifact with legalHold and hide content`() = runBlocking {
        val reportId = "report123"
        val artifactId = "art123"
        
        val reportRef = mockk<DocumentReference>(relaxed = true)
        val artifactRef = mockk<DocumentReference>(relaxed = true)
        
        every { firestore.collection("reports").document(reportId) } returns reportRef
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        val transaction = mockk<Transaction>(relaxed = true)
        val transactionTask = mockk<Task<Unit>>(relaxed = true)
        val transactionSlot = slot<Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(transactionSlot)) } returns transactionTask
        
        coEvery { transactionTask.await() } answers {
            transactionSlot.captured.apply(transaction)
            Unit
        }

        val result = repository.resolveReport(reportId, artifactId, ArtifactRepository.ModerationAction.PLACE_ON_LEGAL_HOLD)

        assertTrue(result.isSuccess)
        verify { transaction.update(reportRef, "status", ReportStatus.RESOLVED.name) }
        verify { transaction.update(artifactRef, "moderation.legalHold", true) }
        verify { transaction.update(artifactRef, "moderation.status", ModerationStatus.HIDDEN.name) }
        verify { transaction.update(artifactRef, "isPublic", false) }
    }

    @Test
    fun `revealModerationEvidence should call Cloud Function and return response`() = runBlocking {
        val artifactId = "art123"
        val callable = mockk<HttpsCallableReference>(relaxed = true)
        val task = mockk<Task<HttpsCallableResult>>(relaxed = true)
        val result = mockk<HttpsCallableResult>(relaxed = true)
        
        every { functions.getHttpsCallable("revealModerationEvidence") } returns callable
        every { callable.call(any()) } returns task
        
        val expectedData = mapOf(
            "creatorUid" to "creator123",
            "creatorEmail" to "test@example.com",
            "audioUrl" to "https://signed-url.com",
            "expiresAt" to "2026-08-24T18:00:00Z",
            "audioStatus" to "AVAILABLE"
        )
        every { result.getData() } returns expectedData
        
        coEvery { task.await() } returns result

        val ceeResult = repository.revealModerationEvidence(artifactId)

        assertTrue(ceeResult.isSuccess)
        val response = ceeResult.getOrNull()
        assertEquals("creator123", response?.creatorUid)
        assertEquals("test@example.com", response?.creatorEmail)
        assertEquals("https://signed-url.com", response?.audioUrl)
    }

    @Test
    fun `softDeleteArtifact should update Firestore status and deletedAt`() = runBlocking {
        val artifactId = "art123"
        val artifactRef = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        val transaction = mockk<Transaction>(relaxed = true)
        val transactionTask = mockk<Task<Unit>>(relaxed = true)
        val transactionSlot = slot<Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(transactionSlot)) } returns transactionTask
        
        coEvery { transactionTask.await() } answers {
            transactionSlot.captured.apply(transaction)
            Unit
        }

        val result = repository.softDeleteArtifact(artifactId)

        assertTrue(result.isSuccess)
        verify { transaction.update(artifactRef, "status", ArtifactStatus.DELETED.name) }
        verify { transaction.update(artifactRef, "isPublic", false) }
        verify { transaction.update(artifactRef, "deletedAt", any()) }
    }

    @Test
    fun `isCurrentUserAdmin should return true if isAdmin field is true in Firestore`() = runBlocking {
        val userId = "admin123"
        every { auth.currentUser?.uid } returns userId
        
        val settingsRef = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("users").document(userId).collection("private").document("settings") } returns settingsRef
        
        val snapshot = mockk<DocumentSnapshot>(relaxed = true)
        val task = mockk<Task<DocumentSnapshot>>(relaxed = true)
        
        every { settingsRef.get() } returns task
        coEvery { task.await() } returns snapshot
        every { snapshot.getBoolean("isAdmin") } returns true
        
        val isAdmin = repository.isCurrentUserAdmin()
        
        assertTrue(isAdmin)
    }
}
