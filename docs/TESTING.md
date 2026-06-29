# Testing Guide: Comment Unlock Pipeline

This guide describes how to run the end-to-end integration tests for the "Listen Before You Respond" comment unlock pipeline.

## Prerequisites

1. **Firebase CLI**: Install it via `npm install -g firebase-tools`.
2. **Java 11+**: Required for the Firebase Emulator Suite.
3. **Android Emulator/Device**: To run the instrumented tests.

## Running Integration Tests

The integration tests run against the **Firebase Emulator Suite** to ensure no data is written to production and to provide a zero-cost testing environment.

### 1. Start the Emulators

In the project root, start the required emulators (Firestore, Auth, and Functions):

```bash
firebase emulators:start --only auth,firestore,functions
```

Wait until the emulators are fully started. You can view the Emulator UI at `http://localhost:4000`.

### 2. Run the Test Suite

Open a new terminal or use Android Studio to run the integration tests.

**Via Command Line:**
```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.saurabh.artifact.e2e.CommentUnlockIntegrationTest
```

**Via Android Studio:**
- Navigate to `app/src/androidTest/java/com/saurabh/artifact/e2e/CommentUnlockIntegrationTest.kt`.
- Click the "Run" icon next to the class name.

## Test Scenarios Covered

The `CommentUnlockIntegrationTest` suite validates:
1. **Happy Path**: Complete workflow from valid playback to comment unlock.
2. **Below Threshold**: Ensures users remain locked if threshold is not met.
3. **Cloud Function Failure**: Verifies system stability when receiving malformed data.
4. **Offline Recovery**: Validates that offline engagement correctly syncs and unlocks upon reconnection.
5. **Replay Regression**: Ensures replaying an artifact doesn't break the existing unlock state.

## Troubleshooting

- **Emulator Connection**: If the tests fail to connect, ensure the Android Emulator can reach `10.0.2.2`.
- **Functions Not Triggering**: Ensure you ran `npm run build` in the `functions/` directory before starting the emulators if you changed any Cloud Function code.
- **Permission Denied**: Check the `firestore.rules` if you encounter permission issues; however, the emulators usually bypass some production checks or use local rules.
