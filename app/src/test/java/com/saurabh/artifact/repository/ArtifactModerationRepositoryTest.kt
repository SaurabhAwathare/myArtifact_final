package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
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
import org.junit.Before
import org.junit.Test

class ArtifactModerationRepositoryTest {
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val reportedArtifactDao = mockk<ReportedArtifactDao>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: ArtifactModerationRepository

    @Before
    fun setup() {
        repository = ArtifactModerationRepository(
            auth = auth,
            firestore = firestore,
            reportedArtifactDao = { reportedArtifactDao },
            diagnosticLogger = diagnosticLogger
        )
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
        
        val setTask = mockk<com.google.android.gms.tasks.Task<Void>>(relaxed = true)
        every { reportRef.set(any()) } returns setTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
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
    fun `resolveReport with HIDE_ARTIFACT should update report and artifact status`() = runBlocking {
        val reportId = "report123"
        val artifactId = "art123"
        
        val reportRef = mockk<DocumentReference>(relaxed = true)
        val artifactRef = mockk<DocumentReference>(relaxed = true)
        
        every { firestore.collection("reports").document(reportId) } returns reportRef
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        // Mock Transaction
        val transaction = mockk<com.google.firebase.firestore.Transaction>(relaxed = true)
        val transactionTask = mockk<com.google.android.gms.tasks.Task<Unit>>(relaxed = true)
        
        val transactionSlot = slot<com.google.firebase.firestore.Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(transactionSlot)) } returns transactionTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
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
    fun `softDeleteArtifact should update Firestore status and deletedAt`() = runBlocking {
        val artifactId = "art123"
        val artifactRef = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        val transaction = mockk<com.google.firebase.firestore.Transaction>(relaxed = true)
        val transactionTask = mockk<com.google.android.gms.tasks.Task<Unit>>(relaxed = true)
        val transactionSlot = slot<com.google.firebase.firestore.Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(transactionSlot)) } returns transactionTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
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
}
