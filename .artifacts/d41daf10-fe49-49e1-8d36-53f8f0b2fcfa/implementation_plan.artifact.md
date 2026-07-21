# Implementation Plan - Phase 1: Publishing Studio Transcript Review

Implement a creator-only transcript review feature inside the Publishing Studio. This allows creators to review the auto-generated transcript of their draft before publishing, leveraging the existing playback pipeline.

## User Review Required

> [!IMPORTANT]
> This feature is strictly for the creator within the Publishing Studio draft review phase. It does not affect the public artifact view or the publishing pipeline.

## Proposed Changes

### [Component] Publishing Studio (ViewModel & UI)

#### [MODIFY] [PublishingStudioViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioViewModel.kt)
- **Architecture Update:** Do NOT inject `DraftToArtifactMapper`. Instead, observe `playbackCoordinator.currentArtifact` to retrieve the transcript already decoded by the audio system.
- Add `transcript: List<TranscriptSegment>` and `isTranscriptExpanded: Boolean` to `StudioSessionState`.
- Update `sessionState` flow:
    - Include `playbackCoordinator.currentArtifact` in the `combine` block.
    - Implement stability logic: Keep the transcript in state once loaded for a specific `draftId`, even if `currentArtifact` is temporarily cleared (e.g., during internal playback transitions).
- Add `toggleTranscript()` to handle UI interaction.

#### [MODIFY] [PublishingStudioScreens.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioScreens.kt)
- Update `StudioReviewStep` to include a collapsible "Transcript" section below the playback controls.
- Add `TranscriptSection` composable with minimalist styling:
    - "Show Transcript" / "Hide Transcript" toggle button.
    - `AnimatedVisibility` for smooth expansion.
    - Placeholder text "Transcript not available." if empty.
- Wrap `StudioReviewStep` in a `verticalScroll` to ensure accessibility when the transcript is expanded.

## Verification Plan

### Automated Verification
- Verify that the transcript appears in `StudioSessionState` as soon as playback starts.
- Ensure the transcript remains in state if playback is paused.

### Manual Verification
1. Open an existing draft in Publishing Studio.
2. Navigate to the "Review Recording" step.
3. Observe the new "Show Transcript" button.
4. Tap "Show Transcript" and verify the transcript appears smoothly.
5. Tap "Hide Transcript" and verify it collapses.
6. Verify that playback remains functional and responsive while the transcript is expanded.
7. Confirm that the transcript section is ONLY visible in the "Review" step of Publishing Studio.
