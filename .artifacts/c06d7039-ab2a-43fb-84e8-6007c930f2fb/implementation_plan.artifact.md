# Allow METADATA_REQUIRED -> DELETING in ArtifactLifecycle

The goal is to allow artifacts in the `METADATA_REQUIRED` state to transition directly to `DELETING`. This supports the use case where a user decides to delete an artifact while they are adding its metadata.

## Proposed Changes

### [Component: Artifact Model]

Update the lifecycle state machine and verify it with tests.

#### [MODIFY] [ArtifactLifecycle.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/ArtifactLifecycle.kt)
Update the `transitions` map in `ArtifactLifecycle` to include `METADATA_REQUIRED -> DELETING`.

#### [MODIFY] [LifecycleTransitionTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/model/LifecycleTransitionTest.kt)
Add a test case in `transitions are allowed in matrix direction` to verify `ArtifactLifecycle.METADATA_REQUIRED.canTransitionTo(ArtifactLifecycle.DELETING)`.

## Verification Plan

### Automated Tests
- Run `LifecycleTransitionTest` to ensure all transitions, including the new one, work as expected.
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.model.LifecycleTransitionTest"
  ```
