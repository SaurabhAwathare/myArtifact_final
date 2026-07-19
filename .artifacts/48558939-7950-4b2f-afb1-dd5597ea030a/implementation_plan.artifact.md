# Runtime Investigation: Capture the Complete Firebase Storage 403 Response

This plan outlines the steps to perform a minimal runtime investigation of the transcript upload failure in `ArtifactRepository`. The goal is to capture exhaustive diagnostic information without changing application behavior or Firebase configuration.

## Proposed Changes

### [app](file:///F:/Android Project/01/app)

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)

I will modify the `uploadTranscript` method to include comprehensive logging before and after the upload attempt.

- **Before `putBytes`**:
    - Log `FirebaseAuth.currentUser` properties (UID, isAnonymous, providerData, metadata).
    - Trigger `getIdToken(true)` and log the outcome (success/failure, exception, timestamps).
    - Log `FirebaseStorage` bucket and `StorageReference` details (path, name, parent path).
    - Log upload metadata (contentType, customMetadata, size).
- **In `catch` block**:
    - If a `StorageException` occurs, log all its properties: `errorCode`, `httpResultCode`, `message`, `localizedMessage`.
    - Recursively log the entire `Throwable` cause chain (class, message, and stack trace).

## Verification Plan

### Automated Tests
- I will not be writing automated tests as this is a runtime investigation on the live failing environment.

### Manual Verification
- The user will trigger the transcript upload process.
- I will then inspect the logs (via logcat or the diagnostic logger) to extract the captured information.
- I will produce a final report based on these logs.
