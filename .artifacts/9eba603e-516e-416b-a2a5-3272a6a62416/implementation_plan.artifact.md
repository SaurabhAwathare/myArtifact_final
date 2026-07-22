# Investigation & Fix Plan - DraftRepositoryOptimizationTest (Phase 2)

The previous fix for the coroutine scheduler mismatch was successful, but the test now fails due to an incomplete `User` mock.

## User Review Required

> [!IMPORTANT]
> This is a test-only fix to satisfy business logic preconditions in the `AuthorSnapshot` mapping. No production changes are required.

## Proposed Changes

### [app]

#### [MODIFY] [DraftRepositoryOptimizationTest.kt](file:///F:/Android/Project/01/app/src/test/java/com/saurabh/artifact/repository/DraftRepositoryOptimizationTest.kt)
- Update the `User` mock to include non-blank values for `anonymousId`, `anonymousName`, and `anonymousSigil`.

## Verification Plan

### Automated Tests
- Run the specific test:
  `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.repository.DraftRepositoryOptimizationTest.observeDraftAsArtifact should suppress emissions when only progress changes"`
- Confirm it passes.
- Run the full suite again.
