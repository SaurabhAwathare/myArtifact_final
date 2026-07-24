# Phase 2 Implementation Plan: Transcript-Free Publishing

Objective: Refactor the publishing pipeline to allow artifacts to be published without transcripts. This includes skipping transcript generation, storage, and upload while maintaining integrity validation and backward compatibility.

## User Review Required

> [!IMPORTANT]
> The integrity hash in `PublishApprovalRepository` will now be calculated using an empty list JSON `[]` if no transcript is present. This is deterministic and ensures that publication matching still works correctly.

## Proposed Changes

### [Domain Layer]

#### [MODIFY] [PublishingManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingManager.kt)
- **Task**: Make transcript upload optional.
- **Change**: In `performPublish`, Step 3 will be updated to check if `draft.frozenTranscriptJson` is effectively empty (null or `[]`). If so, skip calling `artifactRepository.uploadTranscript` and set `transcriptUrl = null`.
- **Reason**: Avoid unnecessary network calls and storage usage for artifacts without transcripts.

### [Repository Layer]

#### [MODIFY] [PublishApprovalRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/PublishApprovalRepository.kt)
- **Task**: Support transcript-free approval.
- **Change**:
    - In `approveAndFreezeAuto`, handle cases where both `frozenTranscriptJson` and `localTranscriptPath` are missing by defaulting to `emptyList<TranscriptSegment>()`.
    - In `approveAndFreeze`, ensure that the deterministic hash is generated even with an empty transcript list (it will use `[]`).
- **Reason**: Ensure a consistent snapshot can be created even when transcripts are not generated.

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- **Task**: Ensure Firestore document creation/finalization handles `null` transcripts.
- **Change**:
    - In `createArtifactDocument`, ensure the recovered transcript from `frozenTranscriptJson` defaults to `emptyList()` if the JSON is missing or invalid.
    - Verify that `finalizeArtifactDocument` handles `null` `transcriptUrl` correctly (it already uses `transcriptUrl?.let { ... }`).
- **Reason**: Preserve document schema consistency.

### [Security Layer]

#### [MODIFY] [UploadGuard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/UploadGuard.kt)
- **Task**: Verify integrity validation works without transcripts.
- **Change**: No changes expected, but I will double-check that `validateApproval` remains transcript-agnostic (it primarily relies on audio checksum and approval token).

## Verification Plan

### Automated Tests
- **Create/Update Tests**:
    - `PublishingManagerTest`: Test `performPublish` with a draft having `frozenTranscriptJson = null`. Verify `uploadTranscript` is not called and `transcriptUrl` in Firestore is null.
    - `PublishApprovalRepositoryTest`: Test `approveAndFreeze` with an empty transcript list. Verify `snapshotHash` is generated.
    - `ArtifactRepositoryTest`: Test `createArtifactDocument` with `null` `transcriptUrl`.

### Manual Verification
1. Start recording and stop it.
2. In Phase 1, transcription should have been skippable.
3. Proceed to publish.
4. Use `adb shell` or logs to verify `PublishingManager` skips transcript upload.
5. Verify in Firestore that the published artifact has `transcriptUrl: null` and `transcript: []`.
6. Verify in Firebase Storage that no JSON file is present for that artifact ID in `transcripts/`.
