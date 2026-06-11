# Improving Emotion Handling for Mixed and Unclear States

The current emotional analysis engine picks a single dominant emotion, which loses nuance when a user expresses conflicting feelings (Mixed) or when the input lacks emotional keywords (Unclear). This plan introduces explicit states for `MIXED` and `UNCLEAR` to provide a more honest and reflective user experience.

## User Review Required

> [!NOTE]
> - **Mixed Threshold**: I've set the "Mixed" detection threshold to 15% of the total score difference between the top two emotions. This is a heuristic that may need tuning based on user feedback.
> - **Visuals**: "Mixed" will use a Purple/Orange gradient, and "Unclear" will use a Grey/Misty-Blue atmospheric theme.

## Proposed Changes

### Core Models

#### [Emotion.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/Emotion.kt)

- Add `MIXED` and `UNCLEAR` members to the `Emotion` enum.
- Update `label` and `emoji` for both.

```kotlin
    MIXED("Mixed", "🎭"),
    UNCLEAR("Unclear", "🌫️"),
```

---

### Analysis Logic

#### [EmotionAnalyzer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/nlp/EmotionAnalyzer.kt)

- Refactor `analyze()` to:
    - Return `Emotion.UNCLEAR` if no keywords are matched (`totalScore == 0`).
    - Return `Emotion.MIXED` if the top two emotional scores are within a 15% margin of the total score.
    - Maintain `Emotion.NEUTRAL` for low-intensity but identified keywords.
- Improve negation logic to handle intermediate boosters (e.g., "not very happy").

---

### Visuals & UI

#### [EmotionalAudioSurface.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/components/EmotionalAudioSurface.kt)
#### [EmotionalBackground.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/components/EmotionalBackground.kt)

- Update `getEmotionalTheme` to provide specific colors for `MIXED` and `UNCLEAR`.
    - `MIXED`: `Color(0xFF9C27B0)` (Purple) to `Color(0xFFFF9800)` (Orange).
    - `UNCLEAR`: `Color(0xFF9E9E9E)` (Grey) to `Color(0xFF607D8B)` (Blue Grey).

#### [EmotionSelector.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/EmotionSelector.kt)

- Add `Emotion.MIXED` to the `EmotionList` so users can manually select it.

---

## Verification Plan

### Automated Tests
- Run `EmotionAnalyzerTest.kt` to ensure existing detections still work.
- Add new test cases:
    - `detects Mixed emotion when scores are close` (e.g., "I am happy but sad").
    - `returns Unclear when no keywords match` (e.g., "The weather is okay").
    - `handles negation with boosters` (e.g., "I am not very happy").

Command: `./gradlew testDebugUnitTest --tests "com.saurabh.artifact.nlp.EmotionAnalyzerTest"`

### Manual Verification
- Deploy the app and record a voice reflection with mixed sentiments.
- Verify that the player UI reflects the "Mixed" (Purple/Orange) theme.
- Record a reflection with no emotional content and verify it shows "Unclear".
