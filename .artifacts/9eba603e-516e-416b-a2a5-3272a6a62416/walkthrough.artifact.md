# Walkthrough - Fixing Coroutine Scheduler Mismatch in DraftRepositoryOptimizationTest

Resolved the `IllegalStateException` caused by using multiple `TestCoroutineScheduler` instances in a single `runTest` block.

## Changes Made

### Repository Tests

#### [DraftRepositoryOptimizationTest.kt](file:///F:/Android/Project/01/app/src/test/java/com/saurabh/artifact/repository/DraftRepositoryOptimizationTest.kt)

- Linked the `UnconfinedTestDispatcher` to the `testScheduler` provided by `runTest`.
- This ensures that the collection coroutine launched in the test shares the same timing and execution context as the test itself.

```diff
-        val job = launch(UnconfinedTestDispatcher()) {
+        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
```

## Verification Results

### Automated Tests

The specific failure regarding schedulers has been resolved. However, the test now reveals a secondary issue related to mock data completeness:

- **Resolved**: `java.lang.IllegalStateException: Detected use of different schedulers...`
- **New Failure**: `java.lang.IllegalStateException: Attempted to create AuthorSnapshot from incomplete User identity (UID: test_user)`

This confirms that the coroutine collection is now executing successfully, allowing the test to reach the mapping logic.

### Next Steps

The investigation for the new failure in `DraftRepositoryOptimizationTest` is complete. The next task is to apply the fix for the incomplete mock identity.
