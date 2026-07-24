# Implementation Plan - Phase 4: Transcript System Removal & Dead Code Cleanup

Objective: Safely remove unused transcription infrastructure and dead code while preserving the architecture for future audio-based analysis and maintaining backward compatibility for legacy artifacts.

## Proposed Changes

### [Component] Background Workers & Pipeline

#### [DELETE] [TranscriptionWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/TranscriptionWorker.kt)
- Provably unused as it was already a no-op and bypassable.

#### [MODIFY] [PrivacyScanWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/PrivacyScanWorker.kt)
- **Preserve file.**
- Add documentation/comments indicating it is currently inactive and reserved for future audio-based privacy scanning.
- Remove from the active `WorkManager` chain in `PublishingOrchestrator`.

#### [MODIFY] [SafetyAnalysisWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/SafetyAnalysisWorker.kt)
- **Preserve file.**
- Add documentation/comments indicating it is currently inactive and reserved for future audio-based safety evaluation.
- Remove from the active `WorkManager` chain in `PublishingOrchestrator`.

#### [MODIFY] [PublishingOrchestrator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingOrchestrator.kt)
- Remove `transcriptionWork`, `privacyWork`, and `safetyWork` from the `WorkManager` processing chain.
- Simplify `startProcessing` to skip these stages, ensuring the chain transitions directly from `WaveformWorker` to `ProcessingFinalizerWorker`.

### [Component] Repositories & DAOs

#### [MODIFY] [DraftDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DraftDao.kt)
- Keep all fields and methods for backward compatibility.
- Add comments to `updateTranscriptionResult`, `updatePrivacyResult`, and `updateSafetyResult` noting they are not used in the current version's publishing flow.

#### [MODIFY] [RecordingRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/RecordingRepository.kt)
- Keep all methods for backward compatibility.
- Add "Legacy" comments to transcript-related update methods.

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Preserve `fetchTranscript` and `uploadTranscript` (for legacy support).
- Ensure `createArtifactDocument` handles null transcripts gracefully without deprecating any fields.

#### [MODIFY] [PublishApprovalRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/PublishApprovalRepository.kt)
- Simplify transcript loading logic in `approveAndFreezeAuto` and `approveAndFreeze` to prioritize the transcript-free model while maintaining legacy fallbacks.

### [Component] Models & Data Classes

#### [No Changes]
- `ArtifactDraftEntity.kt`, `ArtifactEntity.kt`, and `Artifact.kt` will retain all fields without deprecation to ensure the domain model remains canonical and backward-compatible.

### [Component] Services & UI Components

#### [DELETE] [TranscriptSyncView.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/components/TranscriptSyncView.kt)
- Unused UI component.

#### [DELETE] [PrivacyAnalysisEngine.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/PrivacyAnalysisEngine.kt)
- Dead code primarily used for demo/simulation of transcript scanning.

### [Component] Testing

#### [DELETE] [SensitiveInfoScannerTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/service/SensitiveInfoScannerTest.kt)
- Transcript-only tests.

#### [DELETE] [PrivacyAnalysisEngineTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/service/PrivacyAnalysisEngineTest.kt)
- Testing dead code.

#### [MODIFY] [IdentityScoutTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/IdentityScoutTest.kt)
- Remove transcript-specific test cases while preserving username/title leak detection tests.

## Verification Plan

### Automated Tests
- Run `gradle_build(":app:assembleDebug")` to ensure the project still compiles without the removed files.
- Run remaining tests in `app/src/test` to ensure no regressions in core publishing or playback logic.

### Manual Verification
- Deploy the app and record a new artifact.
- Verify it transitions from RECORDING to REVIEW_REQUIRED smoothly (skipping the removed stages).
- Verify publishing still works and the artifact appears on the home feed.
- Verify legacy artifacts still display their transcripts correctly.

## Deliverables
- **Transcript Removal Report**: A final document summarizing deleted files, modified files, remaining legacy components, and future reintroduction points.
