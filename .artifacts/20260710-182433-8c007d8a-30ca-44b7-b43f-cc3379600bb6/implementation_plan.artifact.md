# Implementation Plan - Phase 7: Firestore Security Rules (Artifact Comment System)

Implement Firestore Security Rules for the `comments` subcollection under `artifacts`. This will resolve `PERMISSION_DENIED` errors while ensuring data integrity and user privacy.

## User Review Required

> [!NOTE]
> I've set up prototype Security Rules to keep the data in Firestore safe. They are designed to be secure by enforcing ownership, validating data types and lengths, and reusing the existing artifact visibility logic. However, you should review and verify them before broadly sharing your app. If you'd like, I can help you harden these rules.

- **Single Source of Truth**: Artifact visibility logic is extracted into a helper function `canReadArtifact(artifactId)` and reused for both Artifacts and Comments. This ensures consistency and maintainability.
- **Soft Delete Only**: The rules only allow updating the `status` field to `DELETED`. This matches the current repository implementation.
- **Sync Note**: A comment is added to the rules to note that `MAX_COMMENT_LENGTH` (1000) must stay synchronized with `CommentConstants.kt`.

## Proposed Changes

### [Firestore Security Rules]

#### [firestore.rules](file:///F:/Android Project/01/firestore.rules)

- Add assumed data model for `comments`.
- Extract `canReadArtifact(artifactId)` helper function.
- Update Artifact `read` rules to use the new helper.
- Implement `isValidComment` validation function.
- Add `match` block for `artifacts/{artifactId}/comments/{commentId}`.

```javascript
// ===============================================================
// Assumed Data Model
// ===============================================================
// ...
// Collection: artifacts/{artifactId}/comments
// Document ID: auto-generated
// Fields:
//   - artifactId: string (required, immutable) - Parent artifact ID
//   - creatorId: string (required, immutable) - UID of the author
//   - author: map (required, immutable) - Snapshot of author identity
//     - anonymousId: string (required)
//     - name: string (required)
//   - text: string (required, 1-1000 chars) - Comment content
//   - status: string (required, enum: ACTIVE, DELETED) - Comment status
//   - createdAt: timestamp (server-generated, immutable)
//   - updatedAt: timestamp (server-generated)
// ===============================================================

// ... existing helper functions ...

// Business Purpose: Centralized visibility check for Artifacts and their sub-collections.
// Security Purpose: Single source of truth for read access.
function canReadArtifact(artifactId) {
  let artifact = get(/databases/$(database)/documents/artifacts/$(artifactId)).data;
  return artifact.get('isPublic', false) == true ||
         (request.auth != null && artifact.get('userId', '') == request.auth.uid) ||
         isGlobalAdmin();
}

// ... inside match /databases/{database}/documents ...

// --- ARTIFACTS (The Hearth) ---
match /artifacts/{artifactId} {
  // Read: Use centralized visibility helper
  allow read: if resource == null || canReadArtifact(artifactId);

  // ... existing artifact rules ...

  // --- COMMENTS SUBCOLLECTION ---
  match /comments/{commentId} {
    // Read: Reuse Artifact visibility logic
    allow read: if resource == null || canReadArtifact(artifactId);

    // Create: Authenticated user, matching creatorId and artifactId, valid data
    allow create: if isAuth() &&
                  isValidComment(request.resource.data) &&
                  request.resource.data.creatorId == request.auth.uid &&
                  request.resource.data.artifactId == artifactId &&
                  // Ensure author.anonymousId matches user's anonymousId
                  request.resource.data.author.anonymousId == get(/databases/$(database)/documents/users/$(request.auth.uid)).data.anonymousId;

    // Update: Only owner can soft-delete (status: DELETED)
    allow update: if isAuth() &&
                  resource.data.creatorId == request.auth.uid &&
                  request.resource.data.diff(resource.data).affectedKeys().hasOnly(['status', 'updatedAt']) &&
                  request.resource.data.status == 'DELETED' &&
                  // Ensure immutable fields didn't change
                  request.resource.data.creatorId == resource.data.creatorId &&
                  request.resource.data.artifactId == resource.data.artifactId;

    // Delete: Hard delete not allowed
    allow delete: if false;
  }
}

// ...
function isValidComment(data) {
  // MAX_COMMENT_LENGTH must sync with CommentConstants.kt (currently 1000)
  return data.keys().hasAll(['artifactId', 'creatorId', 'author', 'text', 'status']) &&
         data.artifactId is string &&
         data.creatorId is string &&
         data.author is map &&
         data.author.keys().hasAll(['anonymousId', 'name']) &&
         data.text is string &&
         data.text.size() >= 1 &&
         data.text.size() <= 1000 &&
         data.status == 'ACTIVE';
}
```

## Verification Plan

### Automated Tests
- Run dry-run deployment to verify syntax:
  `npx -y firebase-tools@latest deploy --only firestore:rules --dry-run`

### Manual Verification
1. **Read Gating**: Verify comments are only visible if the artifact is public or owned by the user.
2. **Create Permission**: Verify an authenticated user can create a comment.
3. **Soft Delete**: Verify the creator can set status to `DELETED`.
4. **Immutability**: Verify that trying to change `text` or `creatorId` on an existing comment is denied.
5. **Ownership**: Verify that User A cannot delete User B's comment.
6. **Data Integrity**: Verify that comments with text > 1000 characters or missing fields are rejected.
