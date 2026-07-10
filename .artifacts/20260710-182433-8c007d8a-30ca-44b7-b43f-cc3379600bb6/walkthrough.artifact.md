# Walkthrough - Phase 7: Firestore Security Rules (Artifact Comment System)

I have implemented and deployed the Firestore Security Rules for the Artifact Comment System. These rules resolve the `PERMISSION_DENIED` errors observed during runtime while ensuring strict data integrity and user privacy.

## Changes

### [Firestore Security Rules]

#### [firestore.rules](file:///F:/Android Project/01/firestore.rules)

- **Assumed Data Model**: Added a detailed documentation block at the top of the rules file defining the schema for Users, Artifacts, and Comments.
- **Single Source of Truth for Visibility**: Extracted Artifact visibility logic into a helper function `canReadArtifact(artifactId)`. This function is now used for both Artifact and Comment read rules, ensuring that if a user can see an Artifact, they can also see its comments.
- **Comment Validation**: Implemented `isValidComment(data)` to enforce:
    - Required fields existence and correct types.
    - Text length constraints (1-1000 characters).
    - Status enum validation.
    - Immutable fields (`artifactId`, `creatorId`, `author`).
- **Scoped Access**:
    - **Create**: Authenticated users can create comments if they provide valid data matching their identity.
    - **Update**: Only the creator can update a comment, and only to perform a "soft delete" (setting `status` to `DELETED`).
    - **Delete**: Hard deletion is explicitly disabled.

## Verification Summary

### Automated Tests
- **Dry-run Deployment**: Verified syntax and compilation.
  - `npx -y firebase-tools@latest deploy --only firestore:rules --dry-run` -> **PASSED**
- **Deployment**: Successfully released to production.
  - `npx -y firebase-tools@latest deploy --only firestore:rules` -> **PASSED**

### "Devil's Advocate" Attack Analysis
- **Public Read Exploit**: Blocked by `canReadArtifact` (checks `isPublic` or owner).
- **Ownership Hijacking**: Blocked by checking `request.resource.data.creatorId == request.auth.uid`.
- **Identity Spoofing**: Blocked by verifying `author.anonymousId` against the user's document in Firestore.
- **Data Corruption**: Blocked by `isValidComment` (type and length checks).
- **Unauthorized Editing**: Blocked by `allow update` restricting affected keys to `status` and `updatedAt`.

### Manual Verification Steps (User to Verify)
1. **Open an Artifact**: Verify the Comment Sheet displays correctly (empty state or comments) instead of an error.
2. **Post a Comment**: Verify successful creation and immediate appearance in the list.
3. **Soft Delete**: Verify the creator can delete their own comment.
4. **Logcat Check**: Verify no more `PERMISSION_DENIED` errors appear for comment operations.
