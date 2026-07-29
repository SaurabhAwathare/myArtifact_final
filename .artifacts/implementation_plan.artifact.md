# Implementation Plan - Playback Hydration Authority

Establish `PlayableArtifactRepository` as the single authoritative resolver for playback hydration to ensure local drafts remain authoritative during the publishing window.

## User Review Required

> [!IMPORTANT]
> This change moves the responsibility of queue hydration from `ArtifactRepository` (remote-focused) to `PlayableArtifactRepository` (domain-focused). This is an architectural shift that ensures consistent "Draft vs. Published" logic across the entire playback lifecycle.

## Proposed Changes

### [Repository Layer]

#### [MODIFY] [PlayableArtifactRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/PlayableArtifactRepository.kt)
- **Dependencies**: Add `DraftToArtifactMapper` and `UserRepository`.
- **New API**: `suspend fun resolveArtifactsByIds(ids: List<String>): Result<List<Artifact>>`
- **Logic**:
    1. Query `DraftDao` for all provided `ids`.
    2. For IDs found in `artifact_drafts`:
        - Fetch the current user's profile from `userRepository.getCachedProfile()`.
        - Map to `Artifact` domain model using `draftToArtifactMapper.map()`.
    3. For IDs **not** found in drafts:
        - Fetch from `artifactRepository.getArtifactsByIds(missingIds)`.
    4. Combine both sets while maintaining the original order of the `ids` list.
- **Update Existing API**: Refactor `resolveArtifact` to use the same internal mapping logic if applicable, ensuring consistency.

### [Playback Layer]

#### [MODIFY] [PlaybackSessionManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackSessionManager.kt)
- **Dependencies**: Replace `Lazy<ArtifactRepository>` with `Lazy<PlayableArtifactRepository>`.
- **Refactor `syncWithController`**:
    - Replace the call to `artifactRepository.get().getArtifactsByIds(mediaIds)` with `playableArtifactRepository.get().resolveArtifactsByIds(mediaIds)`.
- **Refactor `play`**:
    - Ensure any internal resolution uses the same authority if needed.

#### [MODIFY] [PlaybackService.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackService.kt)
- **Dependencies**: Replace `ArtifactRepository` with `PlayableArtifactRepository`.
- **Refactor `onPlaybackResumption`**:
    - Replace `artifactRepository.getArtifactsByIds(idsToFetch)` with `playableArtifactRepository.resolveArtifactsByIds(idsToFetch)`.

---

## Sequence Diagram (Hydration Flow)

```mermaid
sequenceDiagram
    participant PSM as PlaybackSessionManager
    participant PAR as PlayableArtifactRepository
    participant DDAO as DraftDao
    participant AREP as ArtifactRepository
    participant FS as Firestore

    PSM->>PAR: resolveArtifactsByIds(ids)
    PAR->>DDAO: getDraftsByIds(ids)
    DDAO-->>PAR: [DraftA, DraftB]
    PAR->>AREP: getArtifactsByIds([missingIds])
    AREP->>FS: whereIn(documentId, missingIds)
    FS-->>AREP: [ArtifactC]
    AREP-->>PAR: [ArtifactC]
    PAR->>PAR: Map Drafts to Artifacts (status=DRAFT)
    PAR-->>PSM: Result<List[DraftA, DraftB, ArtifactC]>
```

## Verification Plan

### Automated Tests
- **`PlayableArtifactRepositoryTest`**:
    - Verify that `resolveArtifactsByIds` returns local drafts when they exist for given IDs.
    - Verify that it falls back to `ArtifactRepository` for IDs not in drafts.
    - Verify that the returned order matches the input ID order.
- **`PlaybackSessionManagerTest`**:
    - Verify that `syncWithController` now calls `PlayableArtifactRepository`.

### Manual Verification
1.  **Publishing Race Condition**:
    - Start publishing a new artifact.
    - Trigger a playback re-sync (e.g., by rotating the screen or navigating away and back if it triggers a controller re-connect).
    - **Verify**: The player continues to show "Draft" or "Publishing" state. It does **not** switch to the remote "Active" metadata.
    - **Verify**: No `PERMISSION_DENIED` errors appear in the logs for `observeArtifact`.
2.  **Queue Restoration**:
    - Force close the app during playback of a mix of drafts and published artifacts.
    - Re-open the app.
    - **Verify**: The playback queue is restored correctly, with drafts still being treated as drafts.
3.  **Normal Playback**:
    - Play published artifacts from the feed.
    - **Verify**: They continue to resolve and play normally from Firestore/Cache.

## Regression Risks
- **Data Latency**: `getCachedProfile()` is used for draft mapping. If the profile is missing from cache, mapping might fail or use fallbacks.
- **Memory**: Hydrating many drafts at once might trigger multiple `DraftToArtifactMapper` decodes (mitigated by the mapper's internal two-tier cache).

## Confidence Level
**Level 4 (Highest)**: This plan directly addresses the identified trace and restores architectural authority to the appropriate domain component.
