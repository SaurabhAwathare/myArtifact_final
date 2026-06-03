# Robust Artifact Deletion & Local Sync

The current implementation of artifact deletion is functional but lacks consistency across different views. While reporting content immediately hides it locally, deleting an artifact (as an owner) does not update the local Room database, leading to stale data in Paging-based feeds (Discovery). Additionally, some feeds perform a heavy network reload instead of a smooth local state update.

## User Review Required

- **Deletion from Local DB**: I will implement immediate deletion from the local Room database upon successful Firestore deletion. This will automatically update all Paging 3 lists (Discovery Feed) without requiring a manual refresh.
- **Optimistic UI in Feed**: In the "For You" feed, I will manually remove the deleted item from the current state to avoid a full network reload, providing a smoother user experience.
- **Player Integration**: I will add deletion support to the `PlayerViewModel` so users can delete their own artifacts directly from the player view.

## Proposed Changes

### Data Layer: Room Database

#### [ArtifactDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactDao.kt)

- Add a `deleteById` method to remove an artifact from the local cache.

```kotlin
@Query("DELETE FROM artifacts WHERE id = :artifactId")
suspend fun deleteById(artifactId: String)
```

---

### Repository Layer

#### [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)

- Update `deletePublishedArtifact` to synchronize the local Room database immediately after the Firestore document is deleted.

---

### UI Layer: Feed & Player

#### [ForYouFeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/ForYouFeedViewModel.kt)

- Optimize `deleteArtifact` to update the `_feedState` locally instead of calling `loadFeed()`.

#### [PlayerViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)

- Extend `deleteCurrentArtifact` to support published artifacts (if the user is the owner).
- Ensure the player stops and closes after a successful deletion.

## Verification Plan

### Automated Tests
- I will verify that `ArtifactRepository.deletePublishedArtifact` successfully calls `artifactDao.deleteById`.
- I will verify that `ForYouFeedViewModel.deleteArtifact` correctly updates the `Success` state without triggering a new `Loading` state.

### Manual Verification
1. **Delete from Discovery (Paging)**: Delete an artifact and verify it disappears immediately from the Discovery feed without a pull-to-refresh.
2. **Delete from For You**: Delete an artifact and verify the list updates smoothly without a full reload flicker.
3. **Delete from Player**: Start playback of your own artifact, open the player, delete it, and verify the player closes and the artifact is gone.
