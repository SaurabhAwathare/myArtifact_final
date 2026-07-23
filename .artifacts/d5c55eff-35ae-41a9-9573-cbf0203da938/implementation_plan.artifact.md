# Implementation Plan - Finding #3 (Main Thread Disk I/O)

This plan addresses verified Main-thread disk I/O in `RecordingService` to prevent ANRs and ensure smooth UI performance during recording.

## User Review Required

> [!IMPORTANT]
> The fix involves caching the available storage space and updating it every 5 seconds instead of every 50ms. This means the storage warning might be delayed by up to 5 seconds, which is an acceptable trade-off for removing high-frequency I/O from the Main thread.

## Proposed Changes

### [Audio Component]

#### [MODIFY] [RecordingService.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/RecordingService.kt)

- **Storage Caching**:
    - Add a private variable `lastKnownAvailableStorageMb` to cache the storage status.
    - Implement a background loop (coroutine) that updates this value every 5 seconds on `Dispatchers.IO` while recording is active.
- **Heartbeat Update**:
    - Update `startTimer()` to use the cached `lastKnownAvailableStorageMb` instead of calling `storageManager.availableStorageMb` directly.
- **Offload File Operations**:
    - Wrap `storageManager.isStorageAvailable()` in `startRecording()` with `withContext(Dispatchers.IO)`.
    - Wrap `File.exists()`, `File.length()`, and `File.delete()` in `stopRecording()` with `withContext(Dispatchers.IO)`.
    - Wrap `File.exists()` and `File.delete()` in `cancelRecording()` with `withContext(Dispatchers.IO)`.

## Verification Plan

### Automated Tests
- Run `RecordingServiceTest` (if exists) or create a focused test for storage monitoring.
- Run all `:app` unit tests: `./gradlew :app:testDebugUnitTest`

### Manual Verification
- **Recording Stability**: Perform a long recording (>5 minutes) and verify it completes successfully.
- **Storage Warning**: Simulate low storage and verify the warning still appears (though with a slight delay).
- **Smoothness**: Verify the recording timer and UI remain smooth during recording.
- **Stop/Cancel**: Verify stop and cancel flows work correctly without regressions.
- **Rapid Actions**: Rapidly start/stop recording to check for race conditions.
