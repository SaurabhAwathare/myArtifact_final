# Production-Ready Audio Review Architecture Upgrade

This plan outlines the steps to upgrade the existing audio review system to a production-grade architecture focusing on high-trust participation gating.

## User Review Required

> [!IMPORTANT]
> - The migration will change the `artifact_review_evidence` table structure. I will handle this via a Room migration or destructive migration if acceptable (given it's an internal state).
> - I will use 5-second segments for coverage tracking as recommended.

## Proposed Changes

### Domain Layer

#### [ReviewPolicy.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/ReviewPolicy.kt) [NEW]
- Define `ReviewPolicy` data class with:
    - `minCoverage: Float`
    - `minEffort: Float`
    - `requireReachedEnd: Boolean`
    - `maxSpeedPenaltyThreshold: Float` (e.g., speed > 2.0x reduces effort gain)

#### [ReviewEvidence.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/ReviewEvidence.kt) [NEW]
- Define domain model `ReviewEvidence` with:
    - `artifactId: String`
    - `versionTag: String`
    - `durationMs: Long`
    - `coverage: BitSet`
    - `effortMap: Map<Float, Long>` (Speed -> Time spent)
    - `lastUpdated: Long`

---

### Data Layer

#### [ArtifactReviewEvidence.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactReviewEvidence.kt)
- Update Room entity to support `BitSet` (as `ByteArray`) and `effortMap` (as Json string).
- Add `versionTag` field.

#### [Converters.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/Converters.kt)
- Add `BitSet` <-> `ByteArray` converter.
- Add `Map<Float, Long>` <-> `String` converter.

---

### Logic & Tracking Layer

#### [ReviewValidator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/validation/ReviewValidator.kt)
- Update interface to accept `ReviewEvidence` and `ReviewPolicy`.

#### [DefaultReviewValidator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/validation/DefaultReviewValidator.kt)
- Implement policy-based validation.
- Calculate "Adjusted Effort" based on playback speed (penalty for > 2.0x).

#### [ReviewTracker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/validation/ReviewTracker.kt)
- Update `DefaultReviewTracker` to use `BitSet`.
- Implement 5-second segment logic.
- Track time spent at different speeds in `effortMap`.

---

### Service Layer

#### [ReviewAuthorityService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ReviewAuthorityService.kt)
- Update to handle the new `ReviewEvidence` model.
- Pass appropriate `ReviewPolicy` to the validator.
- Implement debounced persistence (5-10 seconds) to protect battery/disk.

## Verification Plan

### Automated Tests
- `ReviewTrackerTest.kt`: Test coverage marking with `BitSet`.
- `ReviewValidatorTest.kt`: Test speed penalty logic and policy enforcement.
- `RoomMigrationTest.kt`: (If time permits) verify DB schema update.

### Manual Verification
- Logcat monitoring of `ReviewAuthorityService` to verify debounced writes.
- UI inspection to ensure participation features remain locked until "meaningful listening" is achieved.
