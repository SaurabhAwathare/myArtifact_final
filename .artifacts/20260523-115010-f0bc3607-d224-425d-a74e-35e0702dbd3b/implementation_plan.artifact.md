# Fix Firebase Permissions and Consolidate Reaction Storage

This plan addresses the `PERMISSION_DENIED` errors in Firestore, the 404 errors in Storage, and ensures that owner-only management actions (Delete/Rename) are available and functional across the app, including the feed.

## User Review Required

> [!IMPORTANT]
> I am consolidating reaction storage into the `reactions_global` collection. Previously, reactions were split across multiple collections (`artifacts/{id}/reactions`, `artifact_reactions`, and `reactions_global`). This change ensures consistency and fixes deletion logic.

## Proposed Changes

### Firestore Security Rules

#### [firestore.rules](file:///F:/Android%20Project/01/firestore.rules)

- Fix `reactions_global` rules to allow `create` and `update` separately from `delete`.
- Add explicit `list` permission for queries.
- Ensure artifact owners can delete any reaction associated with their artifact.
- Add rules for the `reactions` subcollection to ensure legacy data can be cleaned up.

```diff
-    // Global Reactions (for profile liked tab)
-    match /reactions_global/{reactionId} {
-      allow read: if isAuthenticated();
-      allow write: if isAuthenticated() && request.auth.uid == request.resource.data.userId;
-      allow delete: if isAuthenticated() && (
-        request.auth.uid == resource.data.userId ||
-        get(/databases/$(database)/documents/artifacts/$(resource.data.artifactId)).data.userId == request.auth.uid
-      );
-    }
+    // Global Reactions (for profile liked tab and management)
+    match /reactions_global/{reactionId} {
+      allow read, list: if isAuthenticated();
+      allow create: if isAuthenticated() && request.resource.data.userId == request.auth.uid;
+      allow update: if isAuthenticated() && request.resource.data.userId == request.auth.uid;
+      allow delete: if isAuthenticated() && (
+        resource.data.userId == request.auth.uid ||
+        get(/databases/$(database)/documents/artifacts/$(resource.data.artifactId)).data.userId == request.auth.uid
+      );
+    }
```

---

### Repositories

#### [ReactionRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/ReactionRepository.kt)

- Update `reactToArtifact` and `toggleReaction` to write to `reactions_global` using a deterministic ID (`artifactId_userId`).
- This ensures that the global reactions collection is actually populated, enabling the "Liked" tab and clean deletions.

#### [ArtifactRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)

- Update `deletePublishedArtifact` to:
    - Delete comments from the `comments` collection.
    - Delete reactions from the `reactions_global` collection.
    - Cleanup any legacy reactions in the subcollection.
- Update `getLikedArtifacts` to query `reactions_global` instead of the non-existent `artifact_reactions`.

---

### UI Components

#### [ArtifactOptionsSheet.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactOptionsSheet.kt)

- Add "Rename Artifact" and "Delete Artifact" options for the owner.
- These will now be visible even when viewing own artifacts in the feed.

#### [ArtifactCard.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactCard.kt)

- Accept `onDeleteClick` and `onRenameClick` callbacks.
- Pass these to the `ArtifactOptionsSheet`.
- Add a confirmation dialog for deletion (or use the existing `DeleteConfirmationDialog`).

#### [ForYouFeedViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/ForYouFeedViewModel.kt)

- Implement `deleteArtifact` and `renameArtifact` methods.
- These will call `ArtifactRepository` and then refresh the feed state locally.

---

## Verification Plan

### Automated Tests
- I will check for existing repository tests. If they exist, I will run them.
- `gradlew app:testDebugUnitTest --tests "com.saurabh.artifact.repository.*"`

### Manual Verification
1. **Consolidated Reaction Flow**:
   - React to an artifact.
   - Verify in Firestore that `reactions_global` is populated with a document ID `artifactId_userId`.
   - Verify that the "Liked" tab in the profile shows this artifact (after updating `getLikedArtifacts`).
2. **Robust Deletion**:
   - Create an artifact, add a comment, and have another user react to it.
   - Delete the artifact as the owner.
   - Verify that:
     - The artifact document is gone.
     - The storage file is gone (or 404 is handled).
     - The comment in `comments` collection is gone.
     - The reaction in `reactions_global` is gone.
3. **Feed Management**:
   - View your own artifact in the "For You" feed.
   - Open options and verify "Rename" and "Delete" are visible.
   - Perform a rename and verify it updates in the UI.
   - Perform a delete and verify the card disappears from the feed.
