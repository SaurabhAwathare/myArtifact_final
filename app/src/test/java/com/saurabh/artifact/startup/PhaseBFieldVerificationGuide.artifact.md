# Phase B: Physical Device Field Verification Guide

This guide provides the steps for the Artifact Engineering team to verify the Firebase App Check rollout on physical devices before proceeding to enforcement.

## Prerequisites
1. **Physical Device**: A retail Android device with Google Play Services enabled.
2. **Release APK**: Use the signed APK generated at `app/build/outputs/apk/release/app-release.apk`.
3. **Environment**: Ensure the device has a stable internet connection.

---

## 1. Primary Success Path (Device Attestation)

### Steps
1. Install the Release APK via ADB:
   `adb install -r app/build/outputs/apk/release/app-release.apk`
2. Start Logcat with filters:
   `adb logcat -v time | grep -E "Startup|Artifact_STARTUP|Security"`
3. Launch Artifact.
4. **Observe the Startup Island Sequence**:
   - Verify the splash screen stages (Presence -> Discovery -> Immersion -> Ritual -> Stable).
   - Ensure no blocking delays occur during the "Security Provider" or "App Check" steps.

### Expected Logcat Output
```text
I/Startup: Starting Optimized Sequence: ARRIVAL (RescueMode=false)
D/Startup: Starting App Check Attestation (Observational)
...
I/Startup: App Check Verified: Attestation successful
...
I/Startup: Transition: STABLE
I/Artifact_STARTUP: STARTUP_SUCCESS | totalDuration=...
```

### Functional Checklist
- [ ] **Feed**: Browse 5+ Artifacts. Ensure images/text load.
- [ ] **Playback**: Listen to an Artifact audio recording.
- [ ] **Publishing**: Create and publish a short test Artifact.
- [ ] **Commenting**: Post a comment on any Artifact.

---

## 2. Failure Path (Controlled Simulation)

To verify the "Non-Blocking" invariant, we must confirm the app remains usable even if attestation fails.

### Option A: Network Denial
1. Launch the app in **Airplane Mode** (with Wi-Fi off).
2. Launch Artifact.
3. Observe `UNVERIFIED` status in logs but successful entry to the "Offline" or "Cached" UI.

### Option B: Play Services Restriction (Recommended)
1. Go to Device Settings -> Apps -> Google Play Services.
2. **Disable Data Access** or **Clear Cache** to induce a transient failure if possible.
3. Launch Artifact.

### Expected Logcat Output (Failure)
```text
W/Startup: App Check attempt 1 failed: TIMEOUT
W/Startup: App Check attempt 2 failed: ERROR
W/Startup: App Check attempt 3 failed: ERROR
W/Startup: App Check Unverified: All attempts exhausted or failed
I/Startup: Transition: STABLE
```
**Verification**: The user MUST be able to reach the Home screen/Feed even if `SecurityStatus` is `UNVERIFIED`.

---

## 3. Privacy & Diagnostic Audit

Verify that NO sensitive information is leaked in logs:
- [ ] No raw App Check tokens.
- [ ] No Firebase UIDs.
- [ ] No User Emails.
- [ ] No Keystore aliases or passwords.

Check for redact signals in release Logcat:
`D/Artifact_STARTUP: ... | token=[REDACTED]` (if any metadata containing "token" was attempted to be logged).

---

## Next Steps
Once these steps are completed, update the **Phase B Field Verification Report** with the `DEVICE_MODEL`, `OS_VERSION`, and `ATTESTATION_RESULT`.

**DO NOT PROCEED TO PHASE C (ENFORCEMENT) UNTIL THIS IS SIGNED OFF.**
