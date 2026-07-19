# Investigation Results: Duplicate LazyColumn Key Crash

The investigation has successfully identified a deterministic execution path that causes the `java.lang.IllegalArgumentException: Key "<id>" was already used` crash.

## Root Cause

The crash occurs on the **Profile Screen** when viewing the **Drafts tab**.

### The Collision
The `ProfileScreen`'s `LazyColumn` simultaneously renders two different lists that share the same underlying IDs:
1.  **Local Drafts**: Sourced from the local Room database (`ArtifactDraftEntity`).
2.  **Cloud Drafts**: Sourced from Firestore (`Artifact` with `status == DRAFT`).

When a user initiates the publishing process, the artifact exists in both locations with the **exact same ID**.

### Execution Path
1.  **Repository Layer**: `GetProfileDataUseCase` combines `recordingRepository.observeDrafts()` (Local) and `artifactRepository.getUserArtifacts()` (Cloud).
2.  **ViewModel Layer**: `ProfileViewModel` exposes these lists in `ProfileUiState`.
3.  **UI Layer**: [ProfileScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/ProfileScreen.kt) renders both lists in the same `LazyColumn` when the `DRAFTS` tab is selected:

```kotlin
// ProfileScreen.kt (Lines 232-261)
when (uiState.selectedTab) {
    ProfileTab.DRAFTS -> {
        if (uiState.isSelf) {
            draftSection(drafts = uiState.localDrafts, ...) // USES ID AS KEY

            if (uiState.cloudDrafts.isNotEmpty()) {
                userArtifactsList(artifacts = uiState.cloudDrafts, ...) // USES SAME ID AS KEY
            }
        }
    }
}
```

Because both [DraftSection.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/components/DraftSection.kt) and [UserArtifactsList.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/components/UserArtifactsList.kt) use `it.id` as the Compose key, any artifact in the "syncing" window appears twice in the composition tree, triggering the crash.

## Evidence

- **Code Evidence**: The `ProfileScreen` explicitly calls two `items()` blocks with overlapping data sources and identical key logic (`key = { it.id }`).
- **Timing**: This explains why the crash is intermittent—it only happens during the race condition window where an artifact has been created in Firestore but not yet marked as `PUBLISHED` in the local Room database.
- **Key Format**: The reported key `fc183d4c-ba5c-4654-bce4-0935d5dae8a3` is a standard UUID, which is the format used for artifact IDs across both local and remote layers.

## Secondary Observations (Feed)

While the `ProfileScreen` collision is the most likely cause for this specific crash, a secondary potential risk was identified in the main Feed:

- **`PersonalizedPagingSource`**: The `emittedIds` set used for cross-page deduplication is not thread-safe. If Paging 3 were to trigger concurrent `load()` calls (e.g., during rapid scroll with high prefetch), duplicates could theoretically slip through the `filter { it.id !in emittedIds }` check. However, Paging 3's sequential `nextKey` dependency makes this less likely than the `ProfileScreen` bug.

## Recommended Fix

The fix should involve deduplicating the `cloudDrafts` against `localDrafts` in the `GetProfileDataUseCase` or the `ProfileViewModel` before they reach the UI, ensuring that only one representation of a "Draft" is shown at a time.
