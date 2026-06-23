package com.saurabh.artifact.domain.auth

import android.util.Log
import com.saurabh.artifact.util.OnboardingManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.CURRENT_SCHEMA_VERSION
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileAuditTool @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val onboardingManager: OnboardingManager
) {
    suspend fun skipOnboarding() {
        onboardingManager.setOnboardingCompleted(setOf("AUDIT"))
        Log.i("ProfileAudit", "ONBOARDING_SKIPPED")
    }

    suspend fun setupHealthyProfile() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).update(
            mapOf("schemaVersion" to CURRENT_SCHEMA_VERSION)
        ).await()
        Log.i("ProfileAudit", "SETUP_HEALTHY_PROFILE | UID: $uid")
    }

    suspend fun setupLegacyProfile() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).update(
            mapOf("schemaVersion" to 1)
        ).await()
        Log.i("ProfileAudit", "SETUP_LEGACY_PROFILE | UID: $uid")
    }

    suspend fun setupCorruptedProfile() {
        val uid = auth.currentUser?.uid ?: return
        // Corrupt by setting a field to a wrong type
        firestore.collection("users").document(uid).update(
            mapOf("resonanceInCount" to "CORRUPTED_STRING")
        ).await()
        Log.i("ProfileAudit", "SETUP_CORRUPTED_PROFILE | UID: $uid")
    }
}
