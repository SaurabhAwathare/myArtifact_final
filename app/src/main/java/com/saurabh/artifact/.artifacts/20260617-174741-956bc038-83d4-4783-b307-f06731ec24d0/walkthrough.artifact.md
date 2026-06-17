# Walkthrough - Navigation Loop Fix

I have implemented a fix for the navigation loop between the Home and Publishing Studio screens.

## Problem
The `PlayerViewModel` was globally observing review progress. When an artifact reached the review threshold (e.g., 95% listened), it would unconditionally trigger navigation to the `PublishingStudio`. This included already-published artifacts, which caused a loop because the Studio screen would immediately finish and return to Home, only for the Player to trigger it again.

## Solution

### 1. Root Cause Fix in `PlayerViewModel`
I updated the review progress observer in `PlayerViewModel` to only emit the navigation event if the current artifact is a draft (`status == ArtifactStatus.DRAFT` or `PENDING_UPLOAD`). This prevents published artifacts from triggering the publication flow.

### 2. Defensive Safeguard in `GlobalOverlayHost`
I added a check in `GlobalOverlayHost` to ensure that a navigation to `PublishingStudio` is only performed if the user is not already on that screen.

## Changes

### [PlayerViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)
```diff
    init {
        viewModelScope.launch {
            reviewSessionManager.reviewProgress
                .map { it.artifactId to it.isThresholdMet }
                .distinctUntilChanged()
                .collect { (artifactId, isThresholdMet) ->
                    if (isThresholdMet && artifactId != null) {
-                        _navigateToPublish.emit(artifactId)
+                        val currentArtifact = uiState.value.currentArtifact
+                        if (currentArtifact?.id == artifactId && currentArtifact.isDraft) {
+                            _navigateToPublish.emit(artifactId)
+                        }
                    }
                }
        }
    }
```

### [GlobalOverlayHost.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/GlobalOverlayHost.kt)
```diff
    // Observe Navigation Events for Review Completion
    LaunchedEffect(Unit) {
        playerViewModel.navigateToPublish.collect { draftId ->
-            onNavigateToPublish(draftId)
+            val isAlreadyInStudio = navController.currentBackStackEntry?.destination?.hasRoute(PublishingStudio::class) == true
+            if (!isAlreadyInStudio) {
+                onNavigateToPublish(draftId)
+            }
        }
    }
```

## Verification Summary

### Automated Checks
- Ran `analyze_file` on both modified files. No errors were found.
- Verified that `Artifact.isDraft` correctly identifies `DRAFT` and `PENDING_UPLOAD` statuses.

### Manual Verification Scenarios (Logic Review)
1.  **Play a published artifact from Feed**: `isDraft` will be `false`, so `navigateToPublish` is never emitted.
2.  **Play your own draft**: `isDraft` is `true`. When threshold is met, `navigateToPublish` is emitted, and the app correctly navigates to `PublishingStudio`.
3.  **Reopen an already published artifact**: Same as scenario 1, no navigation triggered.
4.  **Background -> foreground**: `distinctUntilChanged()` ensures that if the threshold was already met and the event was already handled, it won't re-trigger unless the artifact changes.
