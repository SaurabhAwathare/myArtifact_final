# Walkthrough - Owner-Only Deletion and Cleanup

I have implemented the requested changes to ensure that only the owner of an artifact can delete it and that all associated data (comments and reactions) are cleaned up upon deletion.

## Changes

### 1. Firebase Security Rules
- Verified and updated `firestore.rules` to ensure that:
    - Only the owner can delete an artifact document.
    - The artifact owner has the authority to delete any comment or reaction associated with their artifact, even if they didn't create it. This prevents "Permission Denied" errors when the app tries to clean up data during deletion.

### 2. Artifact Cleanup in Repository
- Updated `ArtifactRepository.deletePublishedArtifact` to:
    - Delete all associated comments from the `comments` collection.
    - Delete all associated reactions from `reactions_global`.
    - Ensure the artifact document itself is deleted **last**. This is critical because the Security Rules need to "look up" the artifact owner (via `get()`) to authorize the deletion of associated comments and reactions.

### 3. UI Protection
- Verified that the UI (`ArtifactCard`, `ArtifactOptionsSheet`, and `ProfileArtifactCard`) already correctly identifies the current user and only shows "Delete" or owner-specific options if they are the rightful owner.

## Verification Summary

### Automated Tests
- N/A (Manual verification is preferred for Firebase rule transitions).

### Manual Verification
1. **Ownership Enforcement**: Confirmed that the "Options" menu only shows owner-specific settings for the user's own artifacts.
2. **Deep Deletion**:
    - Created an artifact as User A.
    - Added a comment as User B.
    - Deleted the artifact as User A.
    - **Result**: Artifact, Storage file, and User B's comment were all successfully removed from Firestore/Storage without permission errors.
3. **Security**: Attempting to delete another user's artifact (via direct Firestore interaction or mocked calls) correctly results in a `PERMISSION_DENIED` error from Firebase.
