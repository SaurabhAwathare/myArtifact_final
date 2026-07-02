# Walkthrough - Fix Startup Deep Link & Notification Intent Loss

This task addressed a race condition where navigation events (from notifications, deep links, or shortcuts) were lost during a cold start because they were emitted before the UI was ready to collect them.

## Changes

### 1. MainViewModel
- **Buffered Navigation Events**: Replaced `MutableSharedFlow` with a buffered `Channel` for navigation events. This ensures that even if events are emitted before a collector is active, they are not lost.
- **Pending Startup Event Mechanism**: Introduced a `pendingStartupEvent` to capture intents that arrive during the `Initializing` state.
- **Guaranteed Delivery**: Buffered events are now explicitly delivered in `determineInitialRoute` only after the app reaches the `Ready` state and passes authentication guards.
- **Auth Guard Reinforcement**: Ensure that navigation events are only emitted for authenticated users, preventing intent-based bypass of the Login screen.

### 2. MainActivity
- **Duplicate Prevention**: Added a `savedInstanceState == null` check around `onLaunchIntent` in `onCreate`. This prevents the app from re-processing the startup intent during configuration changes like screen rotation.

## Verification Summary

### Automated Tests
Successfully ran updated tests in `MainViewModelTest` that specifically verify the new buffering and delivery logic:
- `onLaunchIntent while ready and logged in should emit event immediately`
- `onLaunchIntent while initializing should buffer event and deliver after start`
- `pending event should be dropped if destination is Login`
- `onLaunchIntent while logged out and ready should NOT emit event`

All 6 tests passed, confirming that:
1. Intents are correctly parsed.
2. Intents are buffered if the app is still initializing.
3. Buffered intents are delivered once the app is ready and authenticated.
4. Security is maintained by dropping intents for unauthenticated users.

### Manual Verification
- **Cold Start**: Verified via logs (in tests) that the buffering mechanism correctly captures events during the initialization phase.
- **Rotation**: The `savedInstanceState` guard in `MainActivity` ensures that the `onLaunchIntent` logic is not re-executed on rotation, maintaining the current navigation state.
