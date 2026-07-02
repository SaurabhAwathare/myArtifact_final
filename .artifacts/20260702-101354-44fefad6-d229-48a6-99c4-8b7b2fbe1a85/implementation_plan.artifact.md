# Eliminate StartupCoordinator DATABASE Readiness Hang

Guarantee that `StartupCoordinator` always reaches a terminal state regardless of success or failure, preventing startup hangs when profile registration fails.

## User Review Required

> [!NOTE]
> I am adding a `completeAll()` method to `StartupCoordinator` to unblock all components at once in case of failure. This is safer than manually emitting individual signals in every catch block. I am also adding a `reset()` method to support proper retries after a failure.

## Proposed Changes

### Startup Coordination

Refactor how readiness is signaled to ensure that both success and failure paths in the startup sequence lead to a terminal state in the coordinator.

#### [StartupCoordinator.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt)

- Add `completeAll()` to signal readiness for all components to unblock the sequence on failure.
- Add `reset()` to allow the sequence to be re-run on retry.
- Track the startup coroutine in a `Job` variable to allow cancellation and proper resetting.

```kotlin
    private var startupJob: Job? = null

    fun start() {
        if (isStarted) return
        isStarted = true
        startupJob = scope.launch { ... }
    }

    fun completeAll() {
        Log.w("Startup", "Force completing all readiness signals")
        StartupComponent.entries.forEach { emitReadiness(it) }
    }

    fun reset() {
        startupJob?.cancel()
        isStarted = false
        _readyComponents.value = emptySet()
        _stage.value = StartupStage.ARRIVAL
    }
```

#### [MainViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)

- Call `startupCoordinator.completeAll()` in the `RegistrationResult.Failure` case within `determineInitialRoute()`.
- Call `startupCoordinator.completeAll()` in the general `catch` block of `executeStartup()`.
- Call `startupCoordinator.reset()` in `retryStartup()` before starting the sequence again.

```kotlin
    fun retryStartup() {
        _startupState.value = AppStartupState.Initializing
        startupCoordinator.reset() // Ensure coordinator can restart
        executeStartup()
    }

    private fun executeStartup() {
        viewModelScope.launch {
            try {
                startupCoordinator.start()
                determineInitialRoute()
            } catch (e: Exception) {
                _startupState.value = AppStartupState.Error("An unexpected error occurred.")
                startupCoordinator.completeAll() // Unblock coordinator
            }
        }
    }
```

---

## Verification Plan

### Automated Tests
- **New Test Case** in `MainViewModelTest`: `startup should unblock coordinator on registration failure`.
- **Existing Tests**: Verify that normal startup still works as expected.

### Manual Verification
1.  **Success Path**: Verify normal app startup proceeds through all emotional stages to the Home screen.
2.  **Failure Path**: Simulate registration failure (e.g., throw exception in `RegistrationCoordinator`) and verify:
    - The app shows `StartupErrorScreen` immediately.
    - No 15-second hang occurs.
    - Logs show `Force completing all readiness signals`.
3.  **Retry Path**: Click "Retry" on the error screen and verify the startup sequence restarts.
4.  **Rescue Mode**: Verify that Rescue Mode still works (it already uses a fast-path readiness emission).
