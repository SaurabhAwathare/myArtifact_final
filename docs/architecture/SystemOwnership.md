# Artifact System Ownership Map

This document defines the ownership, source of truth, and primary components for each major subsystem in Artifact.

---

| Subsystem | Owner | Source of Truth | Persistence | Recovery |
| --- | --- | --- | --- | --- |
| **Authentication** | `AuthRepository` | Firebase Auth | SharedPreferences | Auto-restore on startup |
| **Recording** | `RecordingRepository` | Room DB | `artifact_drafts` table | `PublishingRecoveryWorker` |
| **Review** | `ReviewSessionManager` | `EngagementRepository` | `engagement_stats` table | Re-calc on session init |
| **Publishing** | `PublishingOrchestrator` | Room DB | `artifact_drafts` table | `RecoveryWorker` |
| **Media** | `RecordingService` | File System | `/audio` directory | File existence check |
| **Database** | `DraftDao` | Room DB | `drafts.db` | SQLite internal |
| **Workers** | `WorkManager` | System Job Scheduler | System | WorkManager retry policy |

---

## Subsystem Details

### Recording & Publishing
- **Primary Components**: `RecordingRepository`, `PublishingOrchestrator`, `TranscodingWorker`, `UploadWorker`.
- **Constraint**: Must follow all [Publishing Flow Invariants](PublishingFlowInvariants.md).

### Review & Engagement
- **Primary Components**: `ReviewAuthorityService`, `ReviewSessionManager`, `EngagementRepository`.
- **Constraint**: `ReviewAuthorityService` is the only writer for live engagement data.

### Authentication
- **Primary Components**: `AuthRepository`, `IdentityScout`.
- **Constraint**: Must be initialized before any draft-related operations.

*Last Updated: 2026-06-25*
