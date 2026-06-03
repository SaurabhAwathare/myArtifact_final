# Hardening Artifact Audio URL Handling

This plan addresses the risks associated with missing or invalid `audioUrl` fields in artifacts. It ensures a smoother user experience by filtering incomplete content and providing clear feedback for playback failures.

## User Review Required

> [!IMPORTANT]
> - **Content Filtering**: Artifacts without an `audioUrl` will be filtered out from all public feeds (For You, Profile, Search). They will only be visible to the author as "Pending" items.
> - **Playback Errors**: When an audio URL is invalid, the player will show a "Broken Link" error instead of a generic network error.

## Proposed Changes

### [Repository & Data Layer]
Ensures data integrity at the source by filtering out artifacts with missing URLs.

#### [ArtifactRemoteMediator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/paging/ArtifactRemoteMediator.kt)
- Add `whereNotEqualTo("audioUrl", "")` to the Firestore query to prevent empty artifacts from entering the paged feed.
- Ensure the Room `ArtifactEntity` remains consistent with valid data.

#### [FeedRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FeedRepository.kt)
- Update `getDiscoveryCandidates` and `getResonatingArtifacts` queries to exclude artifacts with empty `audioUrl`.
- Add server-side filtering for `audioUrl` presence.

#### [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- In `getCandidateArtifacts`, add a secondary client-side check: `artifact.audioUrl.isNotEmpty()`.
- Add logging for artifacts that pass the Firestore filter but have invalid URLs.

---

### [ViewModel & Domain Layer]
Prevents UI crashes and provides state for error handling.

#### [ForYouFeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/ForYouFeedViewModel.kt)
- Add a safety check in `playArtifact`:
  ```kotlin
  if (artifact.audioUrl.isEmpty()) {
      _feedState.value = FeedCompositionState.Error("This reflection hasn't found its voice yet.")
      return
  }
  ```

#### [PlayerViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)
- Map `Media3` error codes to specific user-friendly messages for missing/invalid URLs (404, 403).

---

### [UI Components]
Provides visual feedback for "broken" or "pending" states.

#### [ArtifactCard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactCard.kt)
- (Optional/Hardening) Add a visual indicator or alpha reduction if the artifact is detected to have a null URL but is still displayed (e.g., in a personal profile).

## Verification Plan

### Automated Tests
- `ArtifactRepositoryTest`: Add a case ensuring `getCandidateArtifacts` returns 0 items if all candidate docs have empty `audioUrl`.
- `ForYouFeedViewModelTest`: Mock a `FeedArtifact` with empty URL and verify `playArtifact` emits the correct error state.

### Manual Verification
1. **Firestore Simulation**: Manually create an artifact document in Firestore with `isPublic = true` but `audioUrl = ""` (empty string).
2. **Feed Verification**: Open the "For You" feed and verify this empty artifact DOES NOT appear.
3. **Profile Verification**: View the author's profile and verify it either doesn't appear or shows as "Incomplete/Pending" (depending on owner status).
4. **Error Handling**: Temporarily hardcode a broken URL in the UI and verify the player shows a "File inaccessible" error rather than hanging.
