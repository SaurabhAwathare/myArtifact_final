# Investigation Report: Media3 SimpleCache Playback Failure

## Problem Statement
Playback fails with an `IllegalStateException` originating from `SimpleCache.getContentMetadata()` during `CacheDataSource.open()`. This error indicates that the `SimpleCache` instance is being used after it has been explicitly released.

## Evidence Collected

### 1. Cache Lifecycle Management
- [MediaCache.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/MediaCache.kt) manages a singleton `SimpleCache`. Its `release()` method calls `instance?.release()` and sets `instance = null`.
- [LogoutCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/LogoutCoordinator.kt) calls `MediaCache.release()` during the logout sequence (Phase D, line 207).

### 2. Stale Cache References
- [SmartDataSourceFactory.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/SmartDataSourceFactory.kt) caches the `SimpleCache` instance in a `val` property:
  ```kotlin
  private val cache = MediaCache.getInstance(context) // line 24
  ```
- This instance is then passed to `CacheDataSource.Factory()` every time a `DataSource` is created (line 35).

### 3. Incomplete Player Release during Logout
- [LogoutCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/LogoutCoordinator.kt) calls `playbackCoordinator.stop()` (line 123), but does not release the `ExoPlayer` or stop the `PlaybackService`.
- `playbackCoordinator.stop()` only calls `playbackSessionManager.stop()`, which merely sends a stop command to the player and does not release it.

### 4. Background Pre-Caching
- [MediaPreCacher.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/MediaPreCacher.kt) uses the same `MediaCache` singleton. Background jobs started here are not cancelled during logout and may continue to use the cache after it is released.

## Findings

- **Lifecycle Mismatch**: The logout sequence releases the global `SimpleCache` while `PlaybackService`, `TransientPlayerManager`, and `MediaPreCacher` may still have active `ExoPlayer` or `CacheWriter` instances.
- **Race Condition**: `LogoutCoordinator` uses a 200ms delay as a "grace period" before clearing state. This is non-deterministic and insufficient to ensure all asynchronous IO operations in the player have ceased.
- **Reference Persistence**: Because `SmartDataSourceFactory` captures the cache instance at construction, it continues to point to a released `SimpleCache` object even if the user logs back in and a new cache instance is initialized in the `MediaCache` singleton.

## Root Cause Analysis
The `IllegalStateException` is a direct result of `MediaCache.release()` being called while the cache is still in use. This happens because the logout process does not fully release all media-related resources (players and background jobs) before destroying the cache they depend on. `SmartDataSourceFactory` further complicates this by holding onto stale, released instances of the cache.

## Confidence Level
**High (Code Evidence)**
The static code analysis reveals a clear architectural flaw where the cache singleton's lifecycle is shorter than the lifecycle of the components that reference it.

## Recommended Fix

1.  **Harden `SmartDataSourceFactory`**: Modify the factory to fetch the latest cache instance from the singleton inside `createDataSource()` rather than caching it in a member variable.
2.  **Explicit Release in Logout**:
    - Update `PlaybackSessionManager` to provide a `release()` method that releases the `MediaController` and stops the `PlaybackService`.
    - Call `release()` on both `PlaybackSessionManager` and `TransientPlayerManager` in `LogoutCoordinator`.
    - Implement a `cancelAll()` method in `MediaPreCacher` and call it during logout.
3.  **Deterministic Cleanup**: Ensure `MediaCache.release()` is only called AFTER all consumers have confirmed they have stopped and released their resources.

## Remaining Unknowns
None. The root cause is fully explained by the identified code patterns.
