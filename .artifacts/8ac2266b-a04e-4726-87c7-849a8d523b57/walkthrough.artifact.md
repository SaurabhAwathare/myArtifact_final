# Walkthrough: On-Demand Feed Recommendations

I have successfully transitioned the feed recommendation explanations from persistent visual labels to an on-demand "Why this Artifact?" action within the options sheet. This change aligns with Artifact's "Calm UX" principles by reducing visual clutter while preserving transparency.

## Changes Made

### 1. Domain Model Refinement
Updated the `FeedRecommendationReason` enum in `FeedModels.kt` to include a dedicated `explanation` property. This allows for more detailed, user-friendly explanations that can evolve independently from the labels used for internal tracking or secondary UI.

```kotlin
RESONATING_PRESENCE(
    label = "From a presence you resonate with",
    explanation = "This presence often shares moments that resonate with your journey."
)
```

### 2. New Explanation Sheet
Created [RecommendationExplanationSheet.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/RecommendationExplanationSheet.kt), a new bottom sheet component that provides a calm, focused space for the recommendation explanation. It includes a clear "I understand" action to dismiss.

### 3. Options Sheet Integration
Updated [ArtifactOptionsSheet.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactOptionsSheet.kt) to include the new "**Why this Artifact?**" option.
- **Condition**: Only visible for artifacts in the "For You" feed with a high-value recommendation reason (Resonance).
- **Suppression**: Hidden for Discovery items to minimize menu noise.

### 4. Feed UI Simplification
- **[ArtifactFeedCard.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactFeedCard.kt)**: Removed the persistent row that displayed the recommendation label.
- **[FeedScreen.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedScreen.kt)**: Refactored the `ArtifactItem` composable to always pass the recommendation metadata to the card, ensuring the "Why this Artifact?" action is correctly populated.

---

## Verification Results

### Manual Verification
- ✅ **For You Feed**: Persistent labels (e.g., "From a presence you resonate with") are no longer visible.
- ✅ **Options Menu**: Tapping the "More" icon on a followed Presence surfaces the "Why this Artifact?" option.
- ✅ **On-Demand Explanation**: Tapping "Why this Artifact?" opens the new bottom sheet with the detailed explanation.
- ✅ **Discovery/Recent Consistency**: Confirmed that "Why this Artifact?" is hidden for discovery candidates and items in the chronological Recent feed.

### Regression Check
- ✅ **Existing Actions**: Verified that Share, Give feedback, Report, and Resonance Settings remain fully functional and correctly ordered in the options sheet.

> [!TIP]
> This change significantly cleans up the feed's vertical rhythm, allowing the user to focus entirely on the audio and the creator's identity.
