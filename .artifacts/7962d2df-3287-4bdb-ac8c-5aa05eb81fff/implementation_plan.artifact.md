# Runtime Verification: Post-Transcoding Processing Pipeline

Verification plan for the transcript-free processing pipeline, tracing from `TRANSCODING_STARTED` to `REVIEW_REQUIRED`.

## User Review Required

> [!IMPORTANT]
> This is a **read-only verification**. No code changes will be made. I will be deploying the app to a device/emulator to capture real-time behavior.

## Proposed Verification Steps

### 1. Deploy & Instrumentation
- **Deploy App**: Build and deploy the current state of the application.
- **Logcat Monitoring**: Set up a continuous Logcat capture filtering for `RECORDING`, `PublishingOrchestrator`, `WM-`, `WORKMANAGER`, and `FINALIZER_TRACE`.

### 2. Execution Trace
- **Trigger Recording**: Initiate a recording session to trigger the processing pipeline.
- **Track Draft ID**: Identify the `draft_id` from the initial `createDraft` call.
- **Timeline Capture**: Record timestamps for:
    - `TRANSCODING_STARTED` (TranscodingWorker)
    - `TRANSCODING_SUCCESS` (TranscodingWorker)
    - Normalization start/end (AudioNormalizationWorker)
    - Waveform start/end (WaveformWorker)
    - `PROCESSING_FINALIZATION_STARTED` (ProcessingFinalizerWorker)
    - `PROCESSING_FINALIZATION_SUCCESS` (ProcessingFinalizerWorker)

### 3. User-Facing Verification
- **State Transition**: Confirm the draft appears in the expected UI state (e.g., transitions from a "Processing" state to a "Review Required" or "Ready" state).
- **Publishing Studio**: Confirm the Publishing Studio opens normally for the draft.
- **Review Status**: Confirm the draft is explicitly marked **Review Required** in the UI.
- **Waveform Visibility**: Confirm waveform data is rendered in the UI.
- **Transcript Absence**: Confirm there are no transcript-related UI elements or errors due to missing transcripts.

### 4. WorkManager Verification
- Use `adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS` or Logcat `WM-WorkerWrapper` to verify:
    - `ENQUEUED`
    - `RUNNING`
    - `SUCCEEDED`
- If a worker never executes, perform root cause analysis.

### 5. Database Verification (Contingency)
- If UI or logs indicate an inconsistency, query the Room database directly:
  `sqlite3 /data/data/com.saurabh.artifact/databases/artifact_drafts.db "SELECT lifecycle, status FROM artifact_drafts WHERE id = '$draft_id'"`

### 6. Gap Analysis
- Identify methods where `diagnosticLogger` calls are missing but would be valuable for debugging stalls.

## Verification Plan

### Automated Tests
- N/A (Manual runtime verification)

### Manual Verification
- Capture and analyze a complete Logcat stream for a single draft.
- Confirm UI state reflects `REVIEW_REQUIRED`.
- Map internal worker success to external UI readiness.
