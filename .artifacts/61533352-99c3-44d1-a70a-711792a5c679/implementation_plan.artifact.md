# Implementation Plan - Standardized Logging Strategy

Introduce a centralized, standardized logging utility `ArtifactLogger` to improve observability, debugging, and production troubleshooting while ensuring security and performance.

## User Review Required

> [!IMPORTANT]
> The existing `DiagnosticLogger` infrastructure will be preserved and utilized internally by `ArtifactLogger`. This ensures that existing session tracking and Crashlytics integration remain intact.

> [!WARNING]
> High-frequency logs (e.g., frame-by-frame progress in audio) will be audited and removed or rate-limited to avoid Logcat flooding.

## Proposed Changes

### Diagnostics Subsystem

#### [MODIFY] [DiagnosticCategory.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/diagnostics/DiagnosticCategory.kt)
- Update the enum to match the requested standardized tags exactly.
- Rename existing tags for consistency (e.g., `NAVIGATION` -> `NAV`).

#### [MODIFY] [LogcatDiagnosticLogger.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/diagnostics/LogcatDiagnosticLogger.kt)
- Refine the `formatLog` method to produce more concise output.
- Ensure `BuildConfig.DEBUG` logic correctly suppresses verbose logs in production.
- Keep the tag prefix as `Artifact_` followed by the category (e.g., `Artifact_AUTH`).

#### [NEW] [ArtifactLogger.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/diagnostics/ArtifactLogger.kt)
- Create a static `object ArtifactLogger` to provide a simple API for logging across the app without requiring Dependency Injection in every class.
- Methods: `v()`, `d()`, `i()`, `w()`, `e()`.
- Supports optional metadata maps and throwables.

### Application Integration

#### [MODIFY] [ArtifactApplication.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ArtifactApplication.kt)
- Initialize `ArtifactLogger` in `onCreate()` using the injected `DiagnosticLogger`.

### Migration and Cleanup

#### [MODIFY] Various Files
- Replace raw `Log.d()`, `Log.i()`, etc., calls with `ArtifactLogger` equivalents.
- Prioritize high-impact areas: `Auth`, `Audio`, `Firestore`, `Sync`.
- Remove redundant "TRACE" logs that clutter the output.
- Ensure no sensitive data (tokens, emails, PII) is being logged.

## Verification Plan

### Automated Tests
- Run existing unit tests to ensure no regressions in application behavior.
- Add a unit test for `ArtifactLogger` to verify it delegates correctly and respects `BuildConfig.DEBUG`.

### Manual Verification
- Deploy the app in Debug mode.
- Perform key workflows (Login, Record, Publish, Comment).
- Filter Logcat by `Artifact_` and verify the output matches the expected format and categories.
- Deploy the app in Release mode (if possible, or simulate via build flag) and verify that only INFO+ logs are present.
