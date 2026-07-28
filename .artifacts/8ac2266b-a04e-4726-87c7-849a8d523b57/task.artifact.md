# Tasks: On-Demand Feed Recommendations

- [x] `[x]` **Domain Model Refinement**
    - [x] Add `explanation` property to `FeedRecommendationReason` in `FeedModels.kt`
- [x] **UI Component Updates**
    - [x] Create `RecommendationExplanationSheet.kt`
    - [x] Update `ArtifactOptionsSheet.kt` to include "Why this Artifact?"
    - [x] Update `ArtifactCard.kt` to handle explanation state
    - [x] Remove persistent label from `ArtifactFeedCard.kt`
- [x] **Logic Integration**
    - [x] Update `FeedScreen.kt` to pass `FeedRecommendationReason`
- [ ] **Verification**
    - [ ] Manual verification of "For You" feed
    - [ ] Manual verification of "Discovery" / "Recent" feed
    - [ ] Regression check of all existing options sheet actions
