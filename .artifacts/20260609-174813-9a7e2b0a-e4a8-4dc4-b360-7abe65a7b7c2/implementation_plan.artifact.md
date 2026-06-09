# Enhanced Device Integrity & Tamper Protection

This plan introduces `IntegrityScout`, a companion to the existing `IdentityScout`. While `IdentityScout` protects user privacy (PII), `IntegrityScout` protects the application environment by detecting rooted devices, emulators, and active debuggers. This creates a "Defense-in-Depth" strategy that complements the existing server-side Play Integrity checks.

## User Review Required

> [!IMPORTANT]
> **Enforcement Policy**: Currently, the plan is to **Warn and Limit** rather than **Hard Block**.
> - Users on rooted devices or emulators will see a "Compromised Environment" warning.
> - "Social" features (Publishing, Resonating) will be restricted on these devices to prevent scraping or automation exploits, but "Consumption" (Listening) will remain available.
> - **Do you prefer a hard block (app won't open) instead?**

## Proposed Changes

### Domain & Security Layer

#### [NEW] [IntegrityScout.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/IntegrityScout.kt)

- Detects common root indicators (`su` binaries, `busybox`, `magisk` paths).
- Detects emulator environments using `Build` metadata (MODEL, HARDWARE, BRAND).
- Detects if a debugger is actively attached.
- Provides an `EnvironmentHealth` score.

#### [StartupCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt)

- Update `initializeSecurityProvider` to run `IntegrityScout` checks.
- Store the result of the integrity scan.
- Ensure that if `PlayIntegrity` fails AND local checks find issues, the `SECURITY` component reflects a `COMPROMISED` state.

---

### ViewModels & UI

#### [MainViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)

- Expose a `deviceIntegrityStatus` Flow.
- Determine if the app should enter "Safe Mode" (disabling uploads/social interactions).

#### [UploadGuard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/UploadGuard.kt)

- Update `validateApproval` to include an environment check.
- Prevent generation of `approvalToken` on compromised devices.

## Verification Plan

### Automated Tests
- `IntegrityScoutTest.kt`: Mocking `Build` and `File` properties to verify detection logic.
- Run tests with: `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.domain.IntegrityScoutTest"`

### Manual Verification
- Deploy to an **Android Emulator**: Verify the app detects it and shows the "Emulated Environment" badge.
- Attempt to **attach a Debugger**: Verify the `isDebuggerConnected` check triggers.
- (If available) Deploy to a **Rooted Device**: Verify root detection paths trigger correctly.
