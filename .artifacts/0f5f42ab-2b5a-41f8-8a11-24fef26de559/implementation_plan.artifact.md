# Implementation Plan - Artifact Recommendation Engine (Phase 4) - Redesign

This plan outlines the design and implementation of a **pipeline-based** recommendation engine for Artifact. This redesign avoids premature optimization of complex engagement metrics and focuses on simplicity, explainability, and the "Listen Before You Respond" philosophy.

## User Review Required

> [!IMPORTANT]
> The recommendation engine is now structured as a **stateless pipeline**. It utilizes existing Firestore fields to avoid backend overhead.

> [!TIP]
> We are introducing an **Exploration Stage** that explicitly reserves space for new creators and "under-heard" Artifacts, directly supporting the mission of discovering authentic human voices.

## Proposed Changes

### [Component] Services

#### [NEW] [RecommendationService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/RecommendationService.kt)
Implement a stateless service that processes a list of artifacts through the following pipeline stages:

1.  **Eligibility Stage**: Filters artifacts based on:
    - `isPublic == true`
    - `status == ACTIVE`
    - `recommendationState == ACTIVE`
    - `moderation.status != HIDDEN`
2.  **Freshness Stage**: Tiers artifacts based on `createdAt`:
    - **Tier 1 (New)**: < 24 hours.
    - **Tier 2 (Recent)**: 24 hours - 7 days.
    - **Tier 3 (Classic)**: > 7 days.
3.  **Quality Stage**: Adjusts priority within tiers using:
    - `reactionCount`: Favoring artifacts that have resonated with others.
    - `playCount`: Identifying "under-heard" content vs "established" content.
4.  **Exploration Stage**:
    - Ensures 15% (configurable) of the feed is reserved for artifacts with `playCount < 5` or from creators who haven't been heard much yet.
5.  **Diversity Stage**:
    - Ensures no consecutive artifacts from the same `userId` appear in the final feed.

```kotlin
class RecommendationService @Inject constructor() {
    fun rank(
        candidates: List<Artifact>,
        config: RecommendationConfig = RecommendationConfig.DEFAULT
    ): List<Artifact> {
        // Implementation of the pipeline
    }
}
```

#### [NEW] [RecommendationConfig.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/RecommendationConfig.kt)
- Define thresholds and percentages (e.g., `explorationRatio = 0.15f`).

---

### [Component] Repositories

#### [MODIFY] [FeedRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FeedRepository.kt)
- Update `getDiscoveryCandidates` to fetch a larger batch of candidates (e.g., 50-100) and delegate the ranking and filtering to `RecommendationService`.
- This ensures the client-side service has enough variety to apply the pipeline effectively.

## Verification Plan

### Automated Tests
- `RecommendationServiceTest.kt`:
    - Verify **Eligibility**: Suppressed or private artifacts never leak into the feed.
    - Verify **Freshness**: Newer artifacts generally appear higher.
    - Verify **Diversity**: No consecutive creator IDs.
    - Verify **Exploration**: Under-heard artifacts are correctly injected into the feed.

### Manual Verification
- Observe the Discovery Feed in the app.
- Verify that "new" artifacts (recorded recently) are prioritized.
- Verify that reporting an artifact (which increases `reportCount` and triggers suppression) removes it from the feed immediately.
- Ensure the feed feels diverse and doesn't just show the same creators.
