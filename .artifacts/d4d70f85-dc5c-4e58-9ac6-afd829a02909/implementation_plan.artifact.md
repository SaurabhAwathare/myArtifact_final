# Production Stabilization Period Plan (30 Days)

This plan outlines the objectives, monitoring strategy, and reporting structure for the 30-day Production Stabilization period following the Phase 1 Migration.

## User Review Required

> [!IMPORTANT]
> **Logging Adjustments**: To strictly meet the tracking requirements (e.g., `cleanup=true`), I propose minor non-invasive changes to logging in `UserRepository`, `ProfileRepairService`, and `UserSessionManager`. Please confirm if these "Stabilization Setup" changes are acceptable despite the "no production code changes" rule.

## Objectives

1.  **Production Monitoring**: 30-day observation window.
2.  **Repair Event Tracking**: Record every `USER_PROFILE_REPAIR_COMPLETED` with timestamp, UID, and cleanup status.
3.  **Migration Health Reports**: Produce weekly summaries of migration progress and anomalies.
4.  **Failure Monitoring**: Proactive tracking of `ProfileRepairService`, `UserSessionManager`, Media3, and Firestore deserialization.
5.  **Sigil Integrity**: Ensure no regressions or user-reported issues with the new Sigil system.
6.  **Avatar Compatibility Audit**: Document remaining "Avatar" code paths that are still being exercised.

## Proposed Changes (Stabilization Instrumentation Patch)

These changes are strictly for observability and do not alter functional behavior, migration logic, or schemas.

### [Component] Authentication & Profile Repair

#### [MODIFY] [UserRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/UserRepository.kt)
- **Log Elevation**: Change `USER_PROFILE_NORMALIZED` log level from `DEBUG` to `INFO`.
- **Metadata Update**: Change `USER_PROFILE_REPAIR_COMPLETED` metadata from `"cleanup" to "LEGACY_SIGIL_FIELDS_REMOVED"` to `"legacyFieldsRemoved" to true`.

#### [MODIFY] [ProfileRepairService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/ProfileRepairService.kt)
- **Infrastructure**: Inject `DiagnosticLogger`.
- **Error Routing**: Replace `Log.e` and `Log.i` with `diagnosticLogger.error` and `diagnosticLogger.info` to ensure production visibility via Crashlytics.
- **Standardized Compatibility Tracking**: Use the standardized `IDENTITY_COMPATIBILITY_PATH_USED` event when legacy fields (e.g., `avatarSeed`) are encountered.
  - Metadata: `source = "Firestore"`, `fallback = true`, `legacyField = "avatarSeed"`, `repairRequired = true/false`.

### [Component] Local Session Management

#### [MODIFY] [UserSessionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/UserSessionManager.kt)
- **Infrastructure**: Inject `DiagnosticLogger`.
- **Standardized Compatibility Tracking**: Log `IDENTITY_COMPATIBILITY_PATH_USED` when legacy DataStore keys (e.g., `AVATAR_SEED`) are read.
  - Metadata: `source = "DataStore"`, `fallback = true`, `legacyField = "avatar_seed"`.

## Monitoring Strategy

### Crashlytics & Logcat
We will monitor the following specific tags/events:
- `PROFILE_REPAIR_IDENTIFIED`: Identify why repairs are happening.
- `USER_PROFILE_REPAIR_FAILED_INVARIANTS`: Critical failure where repair is unsafe.
- `INITIAL_DESERIALIZATION_CRASH`: Firestore schema mismatches.
- `PLAYBACK_FAILED`: Media3 issues, specifically checking for metadata-related errors.

### Weekly Migration Health Report
A new artifact will be generated weekly containing:
- **Repair Velocity**: Number of `USER_PROFILE_REPAIR_COMPLETED` events vs time.
- **Normalization Rate**: Percentage of users reaching "Normalized" state.
- **Fallback Exercise**: Frequency of `LEGACY_AVATAR_FIELDS_EXERCISED` events.
- **Error Log**: Summary of all migration-related non-fatals.

## Verification Plan

### Automated Verification
- **Success Criteria Monitoring**: Track the 30-day "Zero Activity" timer for `USER_PROFILE_REPAIR_COMPLETED`.
- **Crashlytics Velocity Alerts**: Set up for `DiagnosticCategory.AUTH` errors.

### Manual Verification
- **Sigil Visual Audit**: Verify Sigil rendering remains consistent across app restarts and profile updates.
- **Firestore Spot Checks**: Inspect a sample of recently updated user documents to confirm legacy field deletion.

## Success Criteria for Phase 2 Approval
- `USER_PROFILE_REPAIR_COMPLETED` activity < 1% of active users for 30 consecutive days.
- Zero crashes attributed to `ProfileRepairService`.
- No reports of "Avatar" fields being re-introduced.
- All `Media3` metadata fields successfully using `sigil_seed`.
