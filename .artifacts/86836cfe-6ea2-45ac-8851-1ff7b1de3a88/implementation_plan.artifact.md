# Production Readiness Investigation Plan - Multiple PublishingWorker Draft IDs

This plan outlines the steps to investigate why multiple `PublishingWorker` instances with different draft IDs were observed during a single manual publishing verification.

## Goal
Explain the presence of two distinct draft IDs (`0087...` and `9775...`) in the logs during what was expected to be a single publishing session.

## User Review Required
> [!IMPORTANT]
> This is a READ-ONLY investigation. No code changes will be made. The findings will be used to determine if any implementation is required for production readiness.

## Proposed Investigation Steps

### 1. Draft ID Lifecycle Analysis
- [x] Trace draft creation in `RecordingSessionManager` and `RecordingService`.
- [x] Trace draft persistence and approval in `PublishApprovalRepository`.
- [x] Trace publishing handoff in `PublishingOrchestrator`.
- [x] Trace actual upload in `UploadService` and `PublishingWorker`.
- [x] Trace finalization in `PublishingManager` and `DraftRepository`.

### 2. WorkManager Scheduling Verification
- [x] Identify unique work names and policies used for publishing.
- [x] Investigate `PublishingRecoveryWorker` for potential re-triggering of old drafts.
- [x] Analyze the "Hybrid Solution" (Service + Worker) and how it handles concurrency via `UploadTaskDao`.

### 3. Runtime Evidence Correlation
- [x] Explain the `PublishingWorker RETRY` and `UPLOAD_SERVICE_SUCCESS` for `0087...`.
- [x] Explain the `PublishingWorker SUCCESS` for `9775...`.
- [x] Determine if `9775...` represents a stale draft or a duplicate.

### 4. Cleanup & Cancellation Review
- [x] Verify if completed jobs are correctly handled (skipped vs successful).
- [x] Check for orphaned jobs or missing cancellations.

## Verification Plan
- The final deliverable will be a comprehensive report answering all questions in the problem statement.
- No code changes or tests will be executed as per constraints.
