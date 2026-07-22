# Implementation Plan — Fix Stuck Drafts (Lifecycle Transitions)

This plan addresses the **High Risk** finding where drafts can become "stuck" in the local database because the `ArtifactLifecycle` state machine blocks transitions to `DELETING` from several active states.

## Goal
Update the `ArtifactLifecycle` transition matrix to allow all valid deletion paths. This ensures that `DraftDeletionManager` can always perform a "Soft Delete" (immediate UI hiding) and signal background components to stop work.

## Semantic Contract of `DELETING`
The `DELETING` state represents a terminal cancellation signal. Once a draft enters this state:
1.  **UI Hiding**: It is filtered out of all user-facing flows.
2.  **Cease Work**: Background workers (Backup, Processing) must stop operating on this draft.
3.  **Persistence**: The state is preserved across process death to ensure cleanup resumes.
4.  **Hardware Safety**: Recording components must be stopped before or during this transition.

## User Review Required

> [!IMPORTANT]
> This change modifies the core lifecycle state machine. It is surgical and only adds new allowed transitions to the `DELETING` terminal path.

## Proposed Changes

### app/src/main/java/com/saurabh/artifact/model

#### [MODIFY] [ArtifactLifecycle.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/ArtifactLifecycle.kt)
- Update the `transitions` map to include `DELETING` as an allowed next state for:
    - `RECORDING` (Allows emergency/system cleanup while state is active)
    - `PROCESSING` (Allows user cancel during background work)
    - `READY_TO_PUBLISH` (Allows user delete from final confirmation screen)

### app/src/test/java/com/saurabh/artifact/model

#### [MODIFY] [LifecycleTransitionTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/model/LifecycleTransitionTest.kt)
- Add test cases to verify that `RECORDING`, `PROCESSING`, and `READY_TO_PUBLISH` can now transition to `DELETING`.
- Add tests to ensure `DELETING` is terminal (no illegal outgoing transitions except to `DELETED`).
- Verify that `DELETED` remains terminal.

## Verification Plan

### Automated Tests
1.  **Unit Test**: Run `LifecycleTransitionTest` to ensure the matrix logic is correct.
    - `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.model.LifecycleTransitionTest"`
2.  **State Machine Audit**: Verify no existing code relies on `RECORDING -> DELETING` being blocked for safety.
    - *Verification*: `RecordingSessionManager.cancelSession` correctly stops the service (`ACTION_CANCEL`) before/alongside calling the deletion manager.

### Manual Verification
1.  **Processing Cancel**: Start a recording, stop it, and immediately hit Delete while "Processing" is visible. Verify it disappears immediately.
2.  **Final Step Delete**: Navigate to the **Publishing Studio** for a draft that is `READY_TO_PUBLISH`. Click **Delete**. Verify immediate UI removal.
