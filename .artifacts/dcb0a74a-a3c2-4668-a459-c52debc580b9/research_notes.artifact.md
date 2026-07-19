# Investigation Notes: Duplicate LazyColumn Key Crash

## Observed Symptom
- **Crash**: `java.lang.IllegalArgumentException: Key "fc183d4c-ba5c-4654-bce4-0935d5dae8a3" was already used.`
- **Key Type**: UUID, matching the format of an `artifact.id`.
- **Location**: `FeedScreen.kt` in the `LazyColumn` within `FeedContent`.

## Data Pipeline Analysis

### 1. Feed Source
The feed items come from two potential flows in `FeedViewModel`:
- `artifacts`: Based on `ArtifactRemoteMediator` -> Room (`ArtifactDao`) -> `GetFeedFlowUseCase`.
- `personalizedArtifacts`: Based on `PersonalizedPagingSource` -> `GetPersonalizedFeedFlowUseCase`.

### 2. Transformations
Both flows undergo similar transformations:
1. `Pager.flow` (Paging 3)
2. `PagingData.map` (in UseCase): Maps raw artifacts to `FeedDisplayItem.ArtifactItem`.
3. `PagingData.map` (in ViewModel): Applies hydration side-effect and returns the item.
4. `FeedSeparatorMapper.mapToDisplayItems`: Uses `PagingData.insertSeparators` to inject `BreakItem`s.
5. `cachedIn(viewModelScope)`.

### 3. Key Generation in LazyColumn
```kotlin
items(
    count = currentArtifacts.itemCount,
    key = currentArtifacts.itemKey { it.id }
)
```
- `ArtifactItem.id` = `artifact.id`
- `BreakItem.id` = `"break_after_${before.id}"`

## Potential Root Causes & Analysis

| Hypothesis | Source | Risk Level | Evidence / Counter-evidence |
| :--- | :--- | :--- | :--- |
| **Firestore Duplicates** | Firestore | Low | Document IDs are unique. `ArtifactRemoteMediator` and `FeedRepository` use `doc.id` as the artifact ID. |
| **Room Duplicates** | Room | Very Low | `ArtifactEntity.id` is `@PrimaryKey`. `insertAll` uses `REPLACE`. |
| **Paging Cross-Page Duplicates** | `PersonalizedPagingSource` | Medium | `emittedIds` (Set) is used to deduplicate across pages. However, it's not thread-safe and is reset on `PagingSource` invalidation. |
| **Paging Same-Page Duplicates** | `PersonalizedPagingSource` | Low | `distinctBy { it.id }` is called on the combined list before emitting. |
| **Separator ID Collision** | `FeedSeparatorMapper` | Medium | `BreakItem` IDs are prefixed with `break_after_`. Collision with artifact UUID is unlikely. |
| **Duplicate Separator Insertion** | `FeedSeparatorMapper` | High | If `pagingData` has duplicate artifacts, `insertSeparators` will insert duplicate breaks. |
| **Absolute Index Collision** | `ArtifactDao` / `PersonalizedPagingSource` | Medium | `FeedSeparatorMapper` uses `absoluteIndex` for logic. If indices are not unique, logic might trigger multiple times. |
| **Refresh Trigger Race** | `FeedViewModel` | High | `_refreshTrigger` restarts the flow via `flatMapLatest`. `LazyPagingItems` might temporarily hold both old and new data during diffing. |

## Detailed Suspicion: `PersonalizedPagingSource`
In `PersonalizedPagingSource.kt`, `emittedIds` is a `mutableSetOf<String>`.
If the Paging library re-triggers a load for an existing page (e.g., due to internal invalidation or retry), and the `PagingSource` instance is the same, `emittedIds` might cause the retry to return an empty list because the IDs were already "emitted" in the failed attempt.
While this causes missing items, it doesn't directly cause duplicates *within* the list.

## Detailed Suspicion: `FeedRepository.getResonatingArtifacts`
The chunked fetching logic in `FeedRepository.kt` uses a shared `lastVisible` DocumentSnapshot for all chunks. While this correctly continues from a point in time, it might be susceptible to race conditions or precision loss if `createdAt` timestamps are identical.

## Next Steps
1. **Verify Crash Context**: Confirm if the crash occurs on the "For You" (personalized) or "Recent" feed.
2. **Runtime Logging**: Add logging to `FeedSeparatorMapper` to detect duplicate input artifacts or output items.
3. **Inspect Paging Diffing**: Check if `FeedDisplayItem` equals/hashCode is correct (it's a `data class`, so it should be).

### Open Questions for User
- Does the crash happen reliably when refreshing the feed?
- Does it happen more often on the "For You" or "Recent" tab?
- Have you noticed any artifacts appearing twice in the feed before the crash?
