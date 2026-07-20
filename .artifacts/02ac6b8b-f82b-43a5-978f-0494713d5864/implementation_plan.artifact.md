# Runtime Verification Plan: Published Artifact State Integrity (Revised)

Verify that a published artifact maintains its state (`status = ACTIVE`, `isDraft = false`) across Firestore, Room, and the UI.

## User Review Required

> [!IMPORTANT]
> This plan relies on a **manual publish** by the user to ensure a valid and complete recording workflow, which is difficult to automate reliably via `adb`.

## Proposed Phases

### Phase 1: Manual Publish (User Action)
- [ ] User launches the app on `emulator-5554`.
- [ ] User records a short Artifact (10–20 seconds).
- [ ] User completes the Review step and publishes the Artifact.

### Phase 2: Runtime Observation (Android Studio AI)
- [ ] Monitor Logcat for the following signals:
    - `PUBLISH_STARTED`
    - Upload progress
    - `PUBLISH_STEP_7_FINALIZE`
    - `UPLOAD_SUCCESS`
- [ ] Capture the **Artifact ID** from the logs.

### Phase 3: Firestore Verification
- [ ] Use `firebase-tools` to fetch the document for the captured Artifact ID.
- [ ] Verify:
    - `status == "ACTIVE"`
    - `isDraft == false`
    - `isPublic` and `visibility` are correct.

### Phase 4: Room Verification
- [ ] Observe `ArtifactRepository` hydration logs.
- [ ] Verify that the mapped `Artifact` object passed to the UI has:
    - `status == ACTIVE`
    - `isDraft == false`

### Phase 5: UI Verification
- [ ] Take screenshots of Feed and Profile.
- [ ] Verify:
    - No "Draft" badge.
    - "Share Artifact" is available.

### Phase 6: Restart Verification
- [ ] Force stop the app: `adb shell am force-stop com.saurabh.artifact.debug`.
- [ ] Relaunch and verify the artifact state is preserved from Room cache.

### Phase 7: Fresh Device Sync
- [ ] Clear app data: `adb shell pm clear com.saurabh.artifact.debug`.
- [ ] Relaunch, sign in, and verify the artifact syncs from Firestore correctly as `ACTIVE`.

## Verification Report Template

- Firestore Verification ✅/❌
- Room Verification ✅/❌
- Repository Hydration Verification ✅/❌
- Feed Verification ✅/❌
- Profile Verification ✅/❌
- Restart Verification ✅/❌
- Fresh Sync Verification ✅/❌
- **Confidence Level**: [Low/Medium/High]
