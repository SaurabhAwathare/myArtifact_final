# Task List - Production Firebase Configuration Verification

- [ ] **Phase 1: Runtime Evidence Collection**
    - [ ] Monitor Logcat for `INVESTIGATION_LOG` during reproduction.
    - [ ] Capture `AuthUID`, `PathUID`, `DocumentPath`, `ArtifactId`, `Operation`, `Exception`, `ErrorCode`, and `ErrorMessage`.
    - [ ] Capture `Firebase Project ID` from logs.
- [ ] **Phase 2: Decision Branch Analysis**
    - [ ] Compare `AuthUID` vs `PathUID`.
    - [ ] Verify `DocumentPath` correctness.
    - [ ] Branch into Infrastructure (App Check/Rules) or State (Nav/Worker) investigation.
- [ ] **Phase 3: Crash Analysis**
    - [ ] Trace exception propagation from Firestore to UI.
    - [ ] Identify why the exception is not caught/handled before terminating the app.
- [ ] **Phase 4: Root Cause Identification**
    - [ ] Synthesize all evidence.
    - [ ] Provide final recommendation.
