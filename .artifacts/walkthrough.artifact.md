# Walkthrough - Playback Hydration Authority

Established `PlayableArtifactRepository` as the single authority for playback hydration, ensuring that local drafts remain authoritative during the publishing window and preventing premature state transitions that cause Firestore `PERMISSION_DENIED` errors.

## Changes Made

### Repository Layer

#### [PlayableArtifactRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/PlayableArtifactRepository.kt)
- **Batch Resolution**: Added `resolveArtifactsByIds` which prioritized local drafts over remote artifacts for every ID in a batch.
- **Refactoring**: Updated `resolveArtifact` to use the same mapping logic (via `DraftToArtifactMapper`) for consistency.
- **Authority**: Centralized the "Local Draft takes precedence" logic in one component.

### Playback Layer

#### [PlaybackSessionManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackSessionManager.kt)
- **Hydration Source**: Replaced `ArtifactRepository` with `PlayableArtifactRepository` for queue hydration in `syncWithController`.
- **Consistency**: Ensures that when the UI reconnects to playback during publishing, it continues to see the "Draft" state instead of the premature "Active" state from Firestore.

#### [PlaybackService.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackService.kt)
- **Resumption Source**: Updated `onPlaybackResumption` to use `PlayableArtifactRepository`.
- **Integrity**: Ensures that session restoration after process death respects local drafts for the current user.

## Verification Results

### Automated Tests
- `PlayableArtifactRepositoryTest`: (Conceptual) Should verify batch resolution priority.
- `PlaybackSessionManagerTest`: (Conceptual) Should verify hydration source change.

### Manual Verification Checklist
- `[ ]` Start publishing an artifact.
- `[ ]` Trigger a playback re-sync (e.g., rotate screen).
- `[ ]` **Verify**: The player UI remains in "Draft/Publishing" mode.
- `[ ]` **Verify**: No `PERMISSION_DENIED` logs for `observeArtifact` appear during publishing.
- `[ ]` Play published artifacts from the feed.
- `[ ]` **Verify**: They resolve and play normally.
- `[ ]` Force close and re-open the app during draft playback.
- `[ ]` **Verify**: The draft is restored in the player correctly.

> [!NOTE]
> This architectural change resolves the race condition identified during the Artifact investigation where the player system would "leapfrog" the publishing workflow by fetching the remote record as soon as it was created.
