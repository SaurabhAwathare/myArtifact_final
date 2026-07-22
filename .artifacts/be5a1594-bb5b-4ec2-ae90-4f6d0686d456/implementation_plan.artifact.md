# Implementation Plan - Fix hanging UserRepositoryMigrationTest

The `UserRepositoryMigrationTest` is hanging because it uses `Task.await()` extension functions on mocked `Task` objects without initializing `mockkStatic("kotlinx.coroutines.tasks.TasksKt")`. This causes the real `await()` implementation to run, which suspends indefinitely on a mocked task that never "completes".

## Proposed Changes

### [Component: App Tests]

#### [MODIFY] [UserRepositoryMigrationTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/UserRepositoryMigrationTest.kt)
- Add `mockkStatic("kotlinx.coroutines.tasks.TasksKt")` to the `@Before setup()` method.
- Add `mockkStatic("android.util.Log")` and mock it (relaxed) to avoid potential `Method d in android.util.Log not mocked` errors during unit tests.
- Add targeted `unmockkStatic` calls for the above in the `@After tearDown()` method to clean up static mocks between tests without affecting other mocks.

## Verification Plan

### Automated Tests
1. Run the specific hanging tests:
   ```bash
   ./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.repository.UserRepositoryMigrationTest.getOrCreateProfile*"
   ```
2. If they pass, run all unit tests in the module to ensure no regressions:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

### Manual Verification
- Confirm the test execution time is reasonable and does not hang.

## Confidence Level
- **Level 2: Code Evidence** (Root cause identified via static analysis and reproduction of hang, fix pending verification).

---
**Plan approved by user with adjustments.**
