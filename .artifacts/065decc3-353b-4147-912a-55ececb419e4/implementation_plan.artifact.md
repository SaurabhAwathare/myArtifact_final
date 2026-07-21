# Implementation Plan - Phase 2: Local Draft Observation in Player (Final - Revised)

This plan implements Phase 2 of the Artifact metadata observation refactoring. The goal is to avoid unnecessary Firestore listeners for drafts by observing the local `DraftRepository` instead, while maintaining the reactive Flow architecture for the player and strictly adhering to architectural layering.

## User Review Required

> [!IMPORTANT]
> This finalized plan incorporates your latest architectural refinements:
> 1.  **Repository Boundary**: `DraftToArtifactMapper` and local entity mapping are encapsulated within `DraftRepository`.
> 2.  **Symmetry**: Both `observeDraftMetadata` and `observePublishedMetadata` return `Flow<PlayerMetadata>`.
> 3.  **Clean State**: Deletion is treated as state, not metadata. `PlayerMetadata` remains focused on engagement data, and deletion is signaled through the reactive `Artifact?` flow.
> 4.  **Observer Audit**: Verification includes a static audit to ensure `flatMapLatest` correctly manages observer lifecycles.

## Proposed Changes

### Data Layer (Repositories)

#### [MODIFY] [DraftRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/DraftRepository.kt)
- Inject `DraftToArtifactMapper` and `UserRepository`.
- Implement `observeDraftAsArtifact(id: String): Flow<Artifact?>`:
    - Combines `draftDao.observeDraftById(id)` and `userRepository.streamUserProfile(currentUserId)`.
    - Returns `null` if the draft is missing from Room (deleted).
    - Otherwise, returns a mapped `Artifact` using `DraftToArtifactMapper.map(draft, AuthorSnapshot.fromUser(user), "Untitled")`.

### Domain Layer (Player)

#### [MODIFY] [GetPlayerContextUseCase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/player/GetPlayerContextUseCase.kt)

- **Standardize Discriminator**: Use `artifact.isDraft`.
- **Dependency Injection**: Inject `DraftRepository` and `UserRepository`.
- **Branching Logic**:
    - `observeMetadata(artifact)` branches into `observePublishedMetadata(artifact)` and `observeDraftMetadata(artifact)`.
- **Draft Observation Strategy (`observeDraftMetadata`)**:
    - Observe the local draft via `draftRepository.observeDraftAsArtifact(artifact.id)`.
    - If the emitted artifact is `null`, emit `PlayerMetadata()` (default state). The lifecycle of the player itself is managed by the source `artifactFlow`.
    - Otherwise, emit `PlayerMetadata(artifactId = artifact.id)` with default engagement values (private drafts do not have public reactions/resonance).

## Verification Plan

### Automated Tests
- Verify project compilation.
- (Static Analysis) Confirm no direct `ArtifactDraftEntity` or `DraftDao` usage in `GetPlayerContextUseCase`.

### Flow Behavior & Lifecycle Audit (Static Analysis)
- **Duplicate Observers**: Audit `flatMapLatest` usage in `execute` and sub-methods. Confirm that switching artifacts (Draft A → Draft B, Draft → Published) always cancels the previous observation pipeline and starts exactly one new one.
- **Initial Emissions**: Confirm `observeDraftAsArtifact` uses `combine` properly so it doesn't stall if a profile or draft is initially missing.
- **Deletion Resilience**: Verify that if a draft is deleted, the metadata flow recovers to a safe default `PlayerMetadata()` state.

### Manual Verification
- N/A as per instructions ("Do not perform runtime verification").
