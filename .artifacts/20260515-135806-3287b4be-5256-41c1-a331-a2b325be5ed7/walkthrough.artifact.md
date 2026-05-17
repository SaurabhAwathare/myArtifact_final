# Walkthrough - New Reflective Recording Screen

I have implemented the new recording screen design as requested, focusing on a "warm dark-amber" atmosphere and a reflective journaling experience.

## Key Enhancements

### 1. Immersive Atmosphere
- **Animated Background**: A slow-moving gradient shifting between deep black and warm brown creates a calm, late-night feel.
- **Particle System**: Subtly floating amber dots add depth and life to the screen, making it feel "alive" without being distracting.

### 2. Guided Reflection (Top Half)
- **Elegant Typography**: Used the `Instrument Serif` font for prompts, providing a cinematic and intimate reading experience.
- **Categorized Prompts**: Added a "☀ Today's Reflection" label and supportive text to encourage first-time users.
- **Seamless Prompt Switching**: The "Next Question" button updates the prompt instantly without affecting the ongoing recording session.

### 3. Reactive Recording (Bottom Half)
- **Dynamic Waveform**: A new `Canvas`-based waveform that is center-weighted and mirrored, responding dynamically to audio amplitudes with organic movement.
- **Dominant Controls**: The record button is now significantly larger (100dp) with a soft red pulse glow, keeping the primary action centered.
- **Enhanced Status**: The timer now includes a pulsing recording indicator and immersive text ("Recording your artifact...").

## Components Overview

- [RecordingAtmosphere.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/components/RecordingAtmosphere.kt): Handles the ambient visual layers.
- [RecordingNewComponents.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/components/RecordingNewComponents.kt): Contains the new `NewPromptSection`, `NewRecordingWaveform`, and `NewRecordingControls`.
- [RecordingScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/RecordingScreen.kt): Orchestrates the overall screen layout and logic.

## Verification Results
- **Build**: Successfully compiled with `./gradlew assembleDebug`.
- **UI Architecture**: Verified the 50/50 split architecture and component placement.
- **Logic**: Confirmed prompt switching and recording controls are correctly wired to the `RecordingViewModel`.
