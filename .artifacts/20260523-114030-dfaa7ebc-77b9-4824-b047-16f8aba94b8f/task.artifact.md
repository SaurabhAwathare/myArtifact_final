# Task Management

- [x] Researching Deletion Logic and Security Rules
    - [x] Read `firestore.rules`
    - [x] Examine `ArtifactRepository.kt` deletion logic
    - [x] Examine `CommentRepository.kt`
- [x] Implement Security Rule Changes
    - [x] Update `firestore.rules` to allow owners to delete others' comments/reactions on their artifacts
- [x] Implement Repository Changes
    - [x] Update `ArtifactRepository.deletePublishedArtifact` to include comment deletion
- [x] Implement UI Changes
    - [x] Pass `currentUserId` to feed components for owner-only action visibility (Verified existing implementation)
- [x] Verification
    - [x] Verify non-owner cannot delete
    - [x] Verify owner can delete artifact with all associated data (including others' comments)
