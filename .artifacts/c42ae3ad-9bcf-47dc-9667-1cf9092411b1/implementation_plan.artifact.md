# Implementation Plan - Terminal Coverage Tracking Fix

Fix the terminal race condition in the Review Coverage Tracking pipeline to ensure the final playback position is processed before review completion is evaluated.

## User Review Required

> [!IMPORTANT]
> This fix ensures that when `STATE_ENDED` is reached, the coverage tracker is explicitly ticked with the final position. This prevents the case where the periodic ticker stops before the last segment is recorded.

## Proposed Changes

### Audio Infrastructure

#### [MODIFY] [ReviewAuthorityService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ReviewAuthorityService.kt)
- Update the `playbackState` collector to explicitly process the final playback position when `Player.STATE_ENDED` is received.
- Ensure the final position is passed to the tracker before `onPlaybackEnded()` is called.
- This guarantees that the `EngagementEvidence` used for validation includes the terminal position.

#### [MODIFY] [ReviewTracker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/validation/ReviewTracker.kt)
- Adjust `onPlaybackTick` to handle the case where `currentPosMs` is exactly `durationMs` or slightly beyond it, ensuring the last possible segment is marked.
- Specifically, ensure that the `segmentIndex < totalSegments` check allows the final segment to be painted when playback finishes.

## Verification Plan

### Static Validation
- Trace `Media3 STATE_ENDED` -> `ReviewAuthorityService` -> `onPlaybackTick(finalPos)` -> `onPlaybackEnded()` -> `ReviewProgress` update -> `ReviewSessionManager` validation.
- Verify that `isAdvancingNormally` check in `DefaultReviewTracker` will still pass for the final tick if it was advancing just before the end.

### Automated Tests
- I will check existing tests for `ReviewAuthorityService` and `ReviewTracker` to ensure no regressions in seek/pause behavior.
- Run `:app:testDebugUnitTest --tests "com.saurabh.artifact.audio.validation.ReviewTrackerTest"` if available.
