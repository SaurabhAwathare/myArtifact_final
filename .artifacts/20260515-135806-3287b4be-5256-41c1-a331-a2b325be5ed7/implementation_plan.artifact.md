# Implementation Plan - New Reflective Recording Screen

This plan outlines the changes to implement a new "warm dark-amber" recording screen focused on creating a reflective journaling experience.

## Proposed Changes

### UI Components

#### [NEW] [RecordingAtmosphere.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/components/RecordingAtmosphere.kt)
- Implements `AnimatedGradientBackground` (Black -> Warm Brown -> Amber).
- Implements `AmbientParticleSystem` (floating amber dots).

#### [NEW] [RecordingNewComponents.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/components/RecordingNewComponents.kt)
- `NewPromptSection`: Top half section with elegant serif typography for prompts.
- `NewRecordingWaveform`: Dynamic center-weighted waveform with amplitude animation.
- `RecordingStatusSection`: Timer and recording status text.
- `NewRecordingControls`: Pause, Record, and Finish buttons with specific sizes and glows.

### Screen Refactoring

#### [RecordingScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/RecordingScreen.kt)
- Refactored to use a `Column` with two halves (45/55 weight).
- Integrated new components for a more immersive and reflective experience.
- Updated `NewWarningSection` to match the new visual style.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project builds successfully.

### Manual Verification
- Deploy the app to a device/emulator.
- Navigate to the Recording Screen.
- **Visual Check**:
    - Verify the "warm dark-amber" theme.
    - Check for animated gradients and floating particles.
    - Verify the serif typography for the prompt.
    - Verify the waveform animation responsiveness to audio.
    - Check the dominant record button with its red glow.
- **Functional Check**:
    - Start recording and verify the timer and waveform.
    - Pause and resume recording.
    - Change prompts using the "Next Question" button and verify it doesn't interrupt recording.
    - Finish recording and verify navigation to the review screen.
