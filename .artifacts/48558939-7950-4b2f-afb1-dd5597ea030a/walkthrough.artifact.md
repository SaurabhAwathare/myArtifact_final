# Runtime Investigation Walkthrough: Firebase Storage 403 Response Capture

I have instrumented the transcript upload pipeline in `ArtifactRepository.kt` to capture every diagnostic detail available during a failure.

## Changes Made

### [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)

I modified `uploadTranscript` to log exhaustive state information before the upload attempt and detailed exception information if it fails.

#### Instrumentation Details:

- **Authentication State**: Captured UID, anonymous status, provider IDs, and metadata (creation/last sign-in).
- **Token Verification**: Forced an ID token refresh and logged the expiration/issued timestamps (without logging the token itself).
- **Storage Context**: Logged bucket name, reference path, and parent path.
- **Upload Metadata**: Logged content type, custom metadata (e.g., `draftId`), and file size.
- **Error Handling**:
    - Logged `StorageException` specific properties: `errorCode`, `httpResultCode`.
    - Recursively traversed the entire exception cause chain, logging the class name, message, and full stack trace for every cause.

## How to Proceed

1.  **Trigger the Failure**: Deploy the application and attempt a transcript upload that is known to fail with HTTP 403.
2.  **Capture Logs**: Monitor the `DiagnosticLogger` (or logcat) for the following tags:
    - `INVESTIGATION_AUTH_STATE`
    - `INVESTIGATION_TOKEN_REFRESH_SUCCESS` / `INVESTIGATION_TOKEN_REFRESH_FAILED`
    - `INVESTIGATION_STORAGE_CONTEXT`
    - `INVESTIGATION_UPLOAD_METADATA`
    - `TRANSCRIPT_UPLOAD_FAILED_INVESTIGATION`
3.  **Report Results**: Once you have the logs, provide them to me, and I will compile the final report using the requested structure.

> [!IMPORTANT]
> The instrumentation is non-intrusive and does not modify any application logic or Firebase configuration. It only adds diagnostic logging.
