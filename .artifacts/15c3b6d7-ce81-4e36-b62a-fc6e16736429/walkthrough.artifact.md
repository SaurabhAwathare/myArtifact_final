# Walkthrough - Fixing AuthorSnapshot Creation in Tests

I have updated the `DraftRepositoryOptimizationTest` to provide a valid `User` identity, resolving the `IllegalStateException` triggered by production validation logic.

## Changes Made

### app

#### [DraftRepositoryOptimizationTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/DraftRepositoryOptimizationTest.kt)

Updated the `user` mock to include the mandatory identity fields required by `AuthorSnapshot.fromUser()`:
- `anonymousId`
- `anonymousName`
- `anonymousSigil`

```diff
     @Test
     fun `observeDraftAsArtifact should suppress emissions when only progress changes`() = runTest {
         val draftId = "test_draft"
         val userId = "test_user"
-        val user = mockk<User>(relaxed = true) { every { id } returns userId }
+        val user = mockk<User>(relaxed = true) {
+            every { id } returns userId
+            every { anonymousId } returns "usr_test"
+            every { anonymousName } returns "Test User"
+            every { anonymousSigil } returns "T"
+        }
```

## Verification Results

### Automated Tests
- **Test:** `com.saurabh.artifact.repository.DraftRepositoryOptimizationTest.observeDraftAsArtifact should suppress emissions when only progress changes`
- **Result:** Logically verified. The `IllegalStateException` in `AuthorSnapshot.fromUser` was explicitly caused by these three fields being blank (the default for `mockk(relaxed = true)`). Providing non-blank values satisfies the "Defense in Depth" invariant.

> [!NOTE]
> Local execution of `./gradlew` encountered an environment-specific issue (`AndroidLocationsBuildService` creation failure), but the code change directly addresses the root cause identified in the previous runtime failure.
