# Implementation Plan - Final Release Validation

This plan outlines the final validation steps for the Phase 1 implementation to ensure the environment is production-ready.

## User Review Required

> [!IMPORTANT]
> This process involves running the full test suite and a production build. It may take several minutes to complete.
> The Firestore emulator must be available on the system to run integration tests.

## Proposed Changes

### Task 1: Android Unit Tests
Execute the complete Android unit test suite to verify repositories, view models, and use cases.
- Command: `./gradlew :app:testDebugUnitTest`

### Task 2: Firestore Emulator Validation
Run the full emulator validation including Cloud Functions and Security Rules.
- **Setup**: Start Firebase emulators (Firestore, Functions, Auth).
- **Cloud Functions Tests**: Run `npm --prefix functions test` to verify aggregations (`commentCount`, `playCount`), cleanup triggers, idempotency, and invariants.
- **Security Rules Tests**: Run `npm --prefix firestore-tests test` to verify security rules and regressions.
- **Teardown**: Stop Firebase emulators.

### Task 3: Final Release Checklist
- **Production Build**: Verify that the production build succeeds using `./gradlew :app:assembleRelease`.
- **Log Review**: Review all test results for any regressions or defects.
- **Consolidation**: Produce the final test results and release recommendation.

## Verification Plan

### Automated Tests
- Gradle test report for `:app`.
- Jest test output for `functions`.
- Mocha test output for `firestore-tests`.

### Manual Verification
- Verify the existence of the release APK/Bundle after `assembleRelease`.
