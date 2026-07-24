# Implementation Plan - Phase 3: Transcript UI Cleanup

Objective: Remove all transcript-related user interface from Artifact while preserving the stable transcript-free publishing pipeline and maintaining backward compatibility for legacy artifacts.

## User Review Required

> [!IMPORTANT]
> This phase focuses purely on UI removal and conditional visibility. Backend logic, database schemas, and transcript loading for legacy support are preserved.

## Proposed Changes

### Publishing Studio

#### [MODIFY] [PublishingStudioViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioViewModel.kt)
- Remove `transcript` and `isTranscriptExpanded` from `StudioSessionState`.
- Remove `isTranscriptExpanded` from `StudioUiState`.
- Remove `lastTranscript` local variable and logic from `sessionState` flow.
- Remove `toggleTranscript()` function.

#### [MODIFY] [PublishingStudioScreens.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioScreens.kt)
- Remove `TranscriptSection` composable.
- Remove `TranscriptSection` call from `StudioReviewStep`.
- Remove unused `com.saurabh.artifact.model.TranscriptSegment` import.

### Artifact Player

#### [MODIFY] [ImmersivePlayerScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ImmersivePlayerScreen.kt)
- Update `PlayerHeader` to accept an `hasTranscript: Boolean` parameter.
- Only show the transcript toggle button in `PlayerHeader` if `hasTranscript` is true.
- Update `ImmersivePlayerScreen` to pass `artifact?.transcript?.isNotEmpty() == true` to `PlayerHeader`.
- Ensure `AnimatedContent` for transcript display only transitions if `hasTranscript` is true; otherwise, always show the emotional audio surface.

### Player Logic

#### [MODIFY] [PlayerViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)
- Keep existing logic as it provides the necessary data for legacy transcript support in the player.
- (Cleanup) Remove `TRANSCRIPT_LOADED` diagnostic log to reduce noise for transcript-free artifacts.

## Verification Plan

### Manual Verification
1. **Publishing Studio Review**:
   - Start a new recording and enter the Publishing Studio.
   - In the **Review** step, confirm that the "Show Transcript" button is completely gone.
2. **Player (New Artifact)**:
   - Play a draft or a newly published artifact.
   - Confirm that the transcript icon (description/document icon) is NOT visible in the player header.
3. **Player (Legacy Artifact)**:
   - Play an artifact known to have a transcript (legacy).
   - Confirm that the transcript icon APPEARS in the header.
   - Confirm that tapping it toggles the synchronized transcript overlay correctly.

### Automated Tests
- Run `gradle_build("app:assembleDebug")` to ensure UI changes didn't break the build.
