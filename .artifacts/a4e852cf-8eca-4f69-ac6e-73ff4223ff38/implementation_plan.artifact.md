# Implementation Plan - Complete FakeDiagnosticLogger Migration for AUTH Tests

Standardize and complete the migration of `AUTH` related unit tests to use `FakeDiagnosticLogger` instead of `mockk<DiagnosticLogger>`. This ensures robust verification of diagnostic events and consistent test patterns.

## User Review Required

> [!NOTE]
> This migration focuses on test files only. No changes will be made to production code.

## Proposed Changes

### AUTH Domain & Repository Tests

#### [MODIFY] [LogoutCoordinatorTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/auth/LogoutCoordinatorTest.kt)
- Initialize `ArtifactLogger.init(fakeLogger)` in `@Before` to capture logs from `AuthRepository.signOut()`.
- Reset with `ArtifactLogger.init(TestNoOpDiagnosticLogger)` in `@After`.

#### [MODIFY] [UserRepositoryMigrationTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/UserRepositoryMigrationTest.kt)
- Rename `diagnosticLogger` to `fakeLogger` to match the project's standard naming for `FakeDiagnosticLogger` instances.
- Initialize `ArtifactLogger.init(fakeLogger)` in `@Before` and reset in `@After`.
- Verify the "Repaired" state to ensure migration logic is correctly tested.

#### [MODIFY] [MainViewModelTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/MainViewModelTest.kt)
- Replace `mockk<DiagnosticLogger>(relaxed = true)` with `FakeDiagnosticLogger`.
- Initialize `ArtifactLogger.init(fakeLogger)` to capture static logging calls.
- Update `tearDown` to reset `ArtifactLogger`.

#### [MODIFY] [RegistrationCoordinatorTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/auth/RegistrationCoordinatorTest.kt) & [AuthRepositoryTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/AuthRepositoryTest.kt)
- Perform a final consistency check to ensure they follow the `ArtifactLogger.init(fakeLogger)` / `tearDown` pattern.

## Verification Plan

### Automated Tests
- Run the following test classes to ensure they pass and correctly verify diagnostic events:
  - `com.saurabh.artifact.domain.auth.RegistrationCoordinatorTest`
  - `com.saurabh.artifact.domain.auth.LogoutCoordinatorTest`
  - `com.saurabh.artifact.repository.AuthRepositoryTest`
  - `com.saurabh.artifact.repository.UserRepositoryMigrationTest`
  - `com.saurabh.artifact.MainViewModelTest`
