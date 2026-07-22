# Fix AuthorSnapshot Creation Failure in DraftRepositoryOptimizationTest

The `DraftRepositoryOptimizationTest` is failing with an `IllegalStateException` because the mock `User` identity provided in the test does not satisfy the production requirements for creating an `AuthorSnapshot`.

## User Review Required

> [!IMPORTANT]
> The fix involves updating the test fixture to include mandatory identity fields (`anonymousId`, `anonymousName`, `anonymousSigil`). No changes to production code are proposed as the validation in `AuthorSnapshot` is an intentional "Defense in Depth" check.

## Proposed Changes

### app

#### [MODIFY] [DraftRepositoryOptimizationTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/DraftRepositoryOptimizationTest.kt)

Update the mock `User` setup to include required fields:
- `anonymousId`
- `anonymousName`
- `anonymousSigil`

```kotlin
val user = mockk<User>(relaxed = true) {
    every { id } returns userId
    every { anonymousId } returns "usr_test"
    every { anonymousName } returns "Test User"
    every { anonymousSigil } returns "T"
}
```

## Verification Plan

### Automated Tests
- Run the specific failing test:
  `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.repository.DraftRepositoryOptimizationTest.observeDraftAsArtifact should suppress emissions when only progress changes"`

### Manual Verification
- Confirm that the `IllegalStateException` is no longer thrown and the test passes or reveals the next actual logic issue.
