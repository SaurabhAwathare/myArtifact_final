# Implementation Plan - Fix Startup Deep Link & Notification Intent Loss

This plan addresses the issue where navigation events (from notifications, deep links, or shortcuts) are lost during a cold start because they are emitted before the Compose UI is ready to collect them.

## Proposed Changes

### [MainViewModel](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)

Modify the navigation event delivery mechanism to ensure events are buffered and delivered exactly once, while respecting authentication states.

- Replace `MutableSharedFlow` with a buffered `Channel` for navigation events.
- Introduce a `pendingStartupEvent` to store intents that arrive before the app is fully initialized.
- Refine `onLaunchIntent` to store events if the app is still initializing.
- Deliver the pending event in `determineInitialRoute` only after the `Ready` state is reached and authentication is confirmed.

```kotlin
// Change navigation event mechanism
private val _navigationEvent = Channel<Any>(capacity = Channel.BUFFERED)
val navigationEvent = _navigationEvent.receiveAsFlow()

private var pendingStartupEvent: Any? = null

fun onLaunchIntent(intent: android.content.Intent?) {
    val event = parseIntent(intent) ?: return

    if (_startupState.value is AppStartupState.Ready) {
        // Immediate delivery if already ready, respecting Auth Guard
        if (authRepository.currentUser.value != null) {
            emitNavigationEvent(event)
        }
    } else {
        // Buffer for delivery after initialization completes
        pendingStartupEvent = event
    }
}

private suspend fun determineInitialRoute() {
    // ... auth logic ...
    _startupState.value = AppStartupState.Ready(destination)

    // Delivery phase: Only deliver to authenticated users
    if (destination !is Login && destination !is Onboarding) {
        pendingStartupEvent?.let {
            emitNavigationEvent(it)
            pendingStartupEvent = null
        }
    } else {
        // Clear pending event if we are stuck at Auth screens
        pendingStartupEvent = null
    }
}
```

---

### [MainActivity](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainActivity.kt)

Prevent duplicate processing of startup intents during configuration changes (like rotation).

- Wrap the `onLaunchIntent` call in `onCreate` with a `savedInstanceState == null` check.
- `onNewIntent` remains unchanged as it always represents a new user action.

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    // ...
    if (savedInstanceState == null) {
        mainViewModel.onLaunchIntent(intent)
    }
    // ...
}
```

---

### [MainViewModelTest](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/MainViewModelTest.kt)

Update tests to accommodate the change from `SharedFlow` to `Channel`. Since `receiveAsFlow()` is used, standard collection should still work, but we may need to ensure `start()` is called to trigger the delivery of pending events in some test cases.

## Verification Plan

### Automated Tests
- Run existing `MainViewModelTest` to ensure no regressions in intent parsing and basic emission.
- `gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.MainViewModelTest"`

### Manual Verification
- **Cold Start (Notification/Deep Link)**: Close the app completely. Click a notification or deep link. Verify the app opens the correct destination, not just the home screen.
- **Warm Start**: With the app in background, click a notification. Verify immediate navigation.
- **Rotation**: Trigger a startup navigation. While on the target screen, rotate the device. Verify it stays on the screen and doesn't re-trigger the navigation logic.
- **Process Recreation**: Use "Don't keep activities" or kill process in background. Return to app. Verify it handles the restoration correctly without duplicate navigation.
- **Auth Guard**: Try a deep link while logged out. Verify it stays on the Login screen and doesn't navigate to the protected content after logging in (unless specifically desired, but here we drop it).
