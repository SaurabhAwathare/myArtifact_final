# Implementation Plan: On-Demand Feed Recommendations (Refined)

Transition feed recommendation explanations from persistent visual labels to an on-demand "Why this Artifact?" menu action within the options sheet.

## User Review Required

> [!IMPORTANT]
> **Refinement Applied**: We are moving away from `AlertDialog` to a **Bottom Sheet** for the explanation to maintain consistency with the Artifact design system. We are also using the domain model `FeedRecommendationReason` directly in the UI for better type safety and future extensibility.

## Proposed Changes

### [Domain Model]

#### [MODIFY] [FeedModels.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/FeedModels.kt)
*   Update `FeedRecommendationReason` to include a dedicated `explanation` property alongside the existing `label`.
*   This allows the feed's internal logic and the user-facing explanation to evolve independently.

### [UI Components]

#### [MODIFY] [ArtifactOptionsSheet.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactOptionsSheet.kt)
*   Add `recommendationReason: FeedRecommendationReason? = null` and `onWhyThisClick: () -> Unit` to parameters.
*   Add a new `OptionItem`: "**Why this Artifact?**" (Icon: `Icons.Rounded.Info`).
*   **Condition**: Only show if `recommendationReason != null` and `recommendationReason != FeedRecommendationReason.DISCOVERY`.

#### [NEW] [RecommendationExplanationSheet.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/RecommendationExplanationSheet.kt)
*   Create a lightweight bottom sheet that displays the `explanation` property of the `FeedRecommendationReason`.
*   Styled to match `ArtifactOptionsSheet` for visual continuity.

#### [MODIFY] [ArtifactCard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactCard.kt)
*   Add `recommendationReason: FeedRecommendationReason? = null` parameter.
*   Manage state for the new explanation sheet: `var showWhySheet by remember { mutableStateOf(false) }`.
*   Pass reason and toggle handler to `ArtifactOptionsSheet`.

#### [MODIFY] [ArtifactFeedCard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactFeedCard.kt)
*   **Surgical Edit**: Delete the `Row` block rendering the persistent label.
*   Update parameters to pass `FeedRecommendationReason` through to `ArtifactCard`.

### [Feature Logic]

#### [MODIFY] [FeedScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedScreen.kt)
*   Update `ArtifactItem` to correctly resolve and pass the `FeedRecommendationReason` domain object to the card components.

---

## Verification Plan

### Automated Tests
*   **UI Test**: Verify "Why this Artifact?" visibility in the options sheet based on the `FeedRecommendationReason` provided.

### Manual Verification
1.  **Feed Observation**: Confirm persistent labels are gone from the "For You" feed.
2.  **Menu Interaction**: Tap "More" on a followed presence; verify "Why this Artifact?" exists.
3.  **Explanation Sheet**: Verify the new bottom sheet displays the detailed explanation.
4.  **Discovery/Recent Check**: Verify the action is **absent** for Discovery items and in the Recent feed.

### Regression Check
*   Verify all existing actions in `ArtifactOptionsSheet` (Share, Report, Feedback, Delete) function exactly as before across all feed types.
