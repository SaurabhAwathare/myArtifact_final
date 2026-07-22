# Implementation Plan - Complete FakeDiagnosticLogger Migration for AUTH Tests

Standardize remaining compatible AUTH-related unit tests to use `FakeDiagnosticLogger`.

## Proposed Changes

### AUTH Component

#### [MODIFY] [MainViewModelTest](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/MainViewModelTest.kt)
- Replace `mockk<DiagnosticLogger>(relaxed = true)` with `FakeDiagnosticLogger()`.
- Update constructor call to use `fakeLogger`.
- **Do NOT** call `ArtifactLogger.init(fakeLogger)` since `MainViewModel` uses the injected `diagnosticLogger` directly.
- Only add diagnostic event assertions if the test is already validating logging behavior. Do not expand the test's scope solely because `FakeDiagnosticLogger` makes it easy.

#### [REVIEW] [RegistrationCoordinatorTest](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/auth/RegistrationCoordinatorTest.kt)
- Confirm consistent usage of `FakeDiagnosticLogger`.
- Currently uses `ArtifactLogger.init(fakeLogger)`. This is correct since `RegistrationCoordinator` uses the static `ArtifactLogger`.

#### [REVIEW] [AuthRepositoryTest](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/AuthRepositoryTest.kt)
- Confirm consistent usage of `FakeDiagnosticLogger`.
- Currently uses `ArtifactLogger.init(fakeLogger)`. This is correct.

#### [REVIEW] [LogoutCoordinatorTest](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/auth/LogoutCoordinatorTest.kt)
- Confirm consistent usage. It passes `fakeLogger` to constructor.
- Only call `ArtifactLogger.init(fakeLogger)` if the code under test actually uses the static `ArtifactLogger`. If `LogoutCoordinator` relies solely on the injected `DiagnosticLogger`, do not add `ArtifactLogger.init()` solely for consistency. (Current analysis suggests it uses only the injected logger).

#### [REVIEW] [UserRepositoryMigrationTest](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/UserRepositoryMigrationTest.kt)
- Confirm consistent usage. It passes `diagnosticLogger` to constructor.

## Verification Plan

### Automated Tests
Run the following tests using Gradle:
- `com.saurabh.artifact.MainViewModelTest`
- `com.saurabh.artifact.domain.auth.RegistrationCoordinatorTest`
- `com.saurabh.artifact.domain.auth.LogoutCoordinatorTest`
- `com.saurabh.artifact.repository.AuthRepositoryTest`
- `com.saurabh.artifact.repository.UserRepositoryMigrationTest`

Command:
```bash
./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.MainViewModelTest"
./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.domain.auth.RegistrationCoordinatorTest"
./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.domain.auth.LogoutCoordinatorTest"
./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.repository.AuthRepositoryTest"
./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.repository.UserRepositoryMigrationTest"
```

### Manual Verification
- Confirm build succeeds.
- Confirm all tests pass.
- No production files changed.
- No business assertions changed.
