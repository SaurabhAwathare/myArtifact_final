# Fix Firebase Permissions and Owner-Only Actions

Based on the Logcat errors and user feedback, the app needs to ensure that ONLY the owner of an artifact can delete it. When an owner deletes their artifact, all associated data (audio file in Storage, reactions in `reactions_global`, and comments in `comments`) must also be deleted, regardless of who created those reactions or comments.

## User Review Required

> [!IMPORTANT]
> I am updating the Firebase Security Rules (`firestore.rules` and `storage.rules`). These changes will enforce that ONLY owners can delete their artifacts and that they have the authority to delete any reactions or comments associated with their artifacts.

## Proposed Changes

### Firebase Security Rules

#### [firestore.rules](file:///F:/Android%20Project/01/firestore.rules)

- **Artifacts**: Ensure only the owner (`resource.data.userId == request.auth.uid`) can delete the artifact. (Already in place, but I will verify).
- **Comments**: Allow the artifact owner to delete any comment on their artifact.
- **Reactions**: Allow the artifact owner to delete any reaction on their artifact to enable clean deletion.

```diff
     // Standardized Comments
     match /comments/{commentId} {
       allow read: if isAuthenticated();
       allow create: if isAuthenticated() && request.resource.data.authorId == request.auth.uid;
       allow delete: if isAuthenticated() && (
         resource.data.authorId == request.auth.uid ||
+        get(/databases/$(database)/documents/artifacts/$(resource.data.artifactId)).data.userId == request.auth.uid
       );
     }

     // Global Reactions (for profile liked tab)
     match /reactions_global/{reactionId} {
       allow read: if isAuthenticated();
       allow write: if isAuthenticated() && request.auth.uid == request.resource.data.userId;
-      allow delete: if isAuthenticated() && request.auth.uid == resource.data.userId;
+      allow delete: if isAuthenticated() && (
+        request.auth.uid == resource.data.userId ||
+        get(/databases/$(database)/documents/artifacts/$(resource.data.artifactId)).data.userId == request.auth.uid
+      );
     }
```

---

### Repository

#### [ArtifactRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)

- Update `deletePublishedArtifact` to also delete all associated comments from the `comments` collection.
- Ensure the artifact document is deleted LAST to allow security rules that use `get` (fetching the artifact owner) to function during the deletion of reactions and comments.

```kotlin
    suspend fun deletePublishedArtifact(artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val doc = artifactRef.get().await()

            if (!doc.exists()) return@withContext Result.failure(Exception("Artifact not found"))

            val audioUrl = doc.getString("audioUrl")

            // 1. Delete from Storage
            if (audioUrl != null) {
                try {
                    storage.getReferenceFromUrl(audioUrl).delete().await()
                } catch (e: Exception) {
                    Log.w("ArtifactRepository", "Storage file deletion failed: $audioUrl", e)
                }
            }

            // 2. Delete all reactions_global
            val reactions = firestore.collection("reactions_global")
                .whereEqualTo("artifactId", artifactId).get().await()
            if (!reactions.isEmpty) {
                firestore.runBatch { batch ->
                    reactions.documents.forEach { batch.delete(it.reference) }
                }.await()
            }

            // 3. Delete all comments
            val comments = firestore.collection("comments")
                .whereEqualTo("artifactId", artifactId).get().await()
            if (!comments.isEmpty) {
                firestore.runBatch { batch ->
                    comments.documents.forEach { batch.delete(it.reference) }
                }.await()
            }

            // 4. Delete Artifact document (LAST)
            artifactRef.delete().await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ArtifactRepository", "Deletion failed", e)
            Result.failure(e)
        }
    }
```

---

### UI Components

#### [ArtifactFeedCard.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactFeedCard.kt)

- Ensure `currentUserId` is passed down so the UI can correctly identify the owner and show the "Delete" option only to them.

---

## Verification Plan

### Manual Verification
1. **Security Test: Non-Owner Deletion Attempt**:
   - Log in as User A. Create an artifact.
   - Log in as User B. Try to delete User A's artifact (via UI if possible, or by mocking a call if UI hides it).
   - Verify that Firebase returns `PERMISSION_DENIED`.
2. **Owner Deletion with Others' Data**:
   - Log in as User A. Create an artifact.
   - Log in as User B. Add a comment and a reaction to User A's artifact.
   - Log back in as User A. Delete the artifact.
   - **Verify**:
     - Artifact document is gone.
     - Audio file in Storage is gone.
     - User B's comment in `comments` is gone.
     - User B's reaction in `reactions_global` is gone.
3. **UI Check**:
   - Verify the "Delete" button only appears for the owner of the artifact in the Feed and Profile.
