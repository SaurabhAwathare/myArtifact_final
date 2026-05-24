package com.saurabh.artifact.util

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Administrative utility for sanitizing legacy data and enforcing identity isolation.
 * TO BE USED BY ADMINS ONLY.
 */
@Singleton
class PrivacyScrubber @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val usersCollection = firestore.collection("users")
    private val artifactsCollection = firestore.collection("artifacts")

    /**
     * Scrubs PII from the public users collection.
     * Moves 'email' and 'displayName' to the private settings subcollection if they exist.
     */
    suspend fun scrubUsers() {
        try {
            val snapshot = usersCollection.get().await()
            Log.i("PrivacyScrubber", "Starting user scrub for ${snapshot.size()} users...")
            
            for (doc in snapshot.documents) {
                val data = doc.data ?: continue
                val userId = doc.id
                val updates = mutableMapOf<String, Any?>()
                val privateData = mutableMapOf<String, Any?>()

                // 1. Identify PII in top-level
                if (data.containsKey("email")) {
                    privateData["email"] = data["email"]
                    updates["email"] = com.google.firebase.firestore.FieldValue.delete()
                }
                
                if (data.containsKey("displayName")) {
                    privateData["realName"] = data["displayName"]
                    updates["displayName"] = com.google.firebase.firestore.FieldValue.delete()
                }

                // 2. Perform atomic migration
                if (updates.isNotEmpty()) {
                    firestore.runTransaction { transaction ->
                        val privateRef = usersCollection.document(userId).collection("private").document("settings")
                        
                        // Delete from top level
                        transaction.update(doc.reference, updates)
                        
                        // Merge into private level
                        transaction.set(privateRef, privateData, SetOptions.merge())
                    }.await()
                    Log.d("PrivacyScrubber", "Scrubbed user $userId")
                }
            }
            Log.i("PrivacyScrubber", "User scrub completed.")
        } catch (e: Exception) {
            Log.e("PrivacyScrubber", "User scrub failed", e)
        }
    }

    /**
     * Scrubs PII from the artifacts collection.
     * Removes 'userId' and 'username' fields, ensuring only 'author' snapshot remains.
     */
    suspend fun scrubArtifacts() {
        try {
            val snapshot = artifactsCollection.get().await()
            Log.i("PrivacyScrubber", "Starting artifact scrub for ${snapshot.size()} artifacts...")

            for (doc in snapshot.documents) {
                val data = doc.data ?: continue
                val updates = mutableMapOf<String, Any?>()

                // Remove legacy identity fields
                if (data.containsKey("userId")) updates["userId"] = com.google.firebase.firestore.FieldValue.delete()
                if (data.containsKey("authorId")) updates["authorId"] = com.google.firebase.firestore.FieldValue.delete()
                if (data.containsKey("username")) updates["username"] = com.google.firebase.firestore.FieldValue.delete()
                
                // Ensure AuthorSnapshot exists
                if (!data.containsKey("author")) {
                    val authorSnapshot = mapOf(
                        "name" to (data["authorAnonymousName"] ?: data["username"] ?: "Anonymous"),
                        "anonymousId" to (data["authorAnonymousId"] ?: "usr_legacy"),
                        "avatarSeed" to (data["avatarSeed"] ?: ""),
                        "avatarColor" to (data["avatarColor"] ?: "#FFD700")
                    )
                    updates["author"] = authorSnapshot
                }

                if (updates.isNotEmpty()) {
                    firestore.runTransaction { transaction ->
                        // 1. Update artifact document (sanitize)
                        transaction.update(doc.reference, updates)
                        
                        // 2. If it has a userId, record ownership in private subcollection
                        val userId = data["userId"] as? String ?: data["authorId"] as? String
                        if (userId != null) {
                            val ownershipRef = usersCollection.document(userId)
                                .collection("private").document("published_artifacts")
                                .collection("artifacts").document(doc.id)
                            transaction.set(ownershipRef, mapOf("createdAt" to (data["createdAt"] ?: com.google.firebase.Timestamp.now())))
                        }
                    }.await()
                    Log.d("PrivacyScrubber", "Scrubbed and recorded ownership for artifact ${doc.id}")
                }
            }
            Log.i("PrivacyScrubber", "Artifact scrub completed.")
        } catch (e: Exception) {
            Log.e("PrivacyScrubber", "Artifact scrub failed", e)
        }
    }
}
