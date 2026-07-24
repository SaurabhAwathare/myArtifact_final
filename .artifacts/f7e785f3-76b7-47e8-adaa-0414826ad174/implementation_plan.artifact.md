# ArtifactRepository Decomposition Implementation Roadmap

This document outlines a phased strategy for decomposing the bloated `ArtifactRepository` into domain-specific repositories to improve maintainability, testability, and dependency isolation.

## User Review Required

> [!IMPORTANT]
> This roadmap proposes a "Bridge Pattern" during transition where the original `ArtifactRepository` delegates to new repositories. This allows for incremental migration of callers without breaking the entire app.

> [!WARNING]
> Several methods in `ArtifactRepository` currently perform orchestration across multiple domains (e.g., updating Firestore and local Room, or calling `UserRepository`). These should be moved to **Managers** or **Coordinators** rather than just another repository.

## Implementation Phases

The extraction is ordered by risk and dependency complexity, starting with non-critical engagement features.

### Phase 1: Artifact Engagement & Social
**Target Repository:** [NEW] `ArtifactInteractionRepository`
**Status:** Discovery

- **Methods to Move:** `saveArtifact`, `unsaveArtifact`, `getSavedArtifactIds`, `getSavedArtifacts`, `recordPlay`, `submitPrivateFeedback`, `saveArtifactToFirestore` (internal), `unsaveArtifactFromFirestore` (internal).
- **Dependencies:** `FirebaseFirestore`, `FirebaseAuth`, `PendingInteractionDao`, `PersonalizationEngine`, `SettingsRepository`, `Context` (for Worker).
- **Expected Impact:** Decouples social interactions and bookmarking from core metadata access.
- **Rollback Strategy:** Keep original methods in `ArtifactRepository` as deprecated wrappers delegating to the new repository.

### Phase 2: Artifact Moderation & Safety
**Target Repository:** [NEW] `ArtifactModerationRepository`
**Status:** Planning

- **Methods to Move:** `submitReport`, `getPendingReports`, `resolveReport`, `renamePublishedArtifact`, `deletePublishedArtifact` (Firestore soft-delete part).
- **Dependencies:** `FirebaseFirestore`, `FirebaseAuth`, `ReportedArtifactDao`.
- **Expected Impact:** Isolates administrative and safety logic.
- **Rollback Strategy:** Revert delegation in `ArtifactRepository`.

### Phase 3: Artifact Publication & Storage
**Target Repository:** [NEW] `ArtifactPublishRepository`
**Status:** Planning

- **Methods to Move:** `uploadArtifactResumable`, `createArtifactDocument`, `finalizeArtifactDocument`, `uploadTranscript`, `fetchTranscript`.
- **Dependencies:** `FirebaseStorage`, `FirebaseFirestore`, `DraftDao`.
- **Expected Impact:** Isolates `FirebaseStorage` and complex upload state management.
- **Rollback Strategy:** Functional parity verification before removing delegation.

### Phase 4: Discovery & Feed
**Target Repository:** [NEW] `ArtifactFeedRepository`
**Status:** Planning

- **Methods to Move:** `getArtifactsPager`, `getUserArtifacts`.
- **Dependencies:** `FirebaseFirestore`, `ArtifactDao`, `AppDatabase`.
- **Expected Impact:** Decouples UI-specific Paging logic from the core data model.
- **Rollback Strategy:** Parallel execution with feature flag if possible.

### Phase 5: Core Metadata & Maintenance
**Target Repository:** [MODIFY] `ArtifactRepository`
**Status:** Planning

- **Retained Methods:** `getArtifact`, `getArtifactById`, `getArtifactsByIds`, `observeArtifact`, `getArtifactDetail`.
- **Added Methods:** Move `runCacheCleanup` to a new `StorageMaintenanceRepository` or keep here as a "Core" maintenance task.
- **Role:** Authority for artifact metadata and local caching.

---

## Dependency Migration Checklist

| Repository | New Dependencies | Removed from `ArtifactRepository`? |
| :--- | :--- | :--- |
| `ArtifactInteractionRepository` | `PendingInteractionDao`, `PersonalizationEngine` | Yes |
| `ArtifactPublishRepository` | `FirebaseStorage`, `DraftDao` | Yes |
| `ArtifactModerationRepository` | `ReportedArtifactDao` | Yes |
| `ArtifactFeedRepository` | `ArtifactDao`, `ArtifactRemoteMediator` | Yes |
| `ArtifactRepository` (Core) | (Minimal: Firestore, `ArtifactDao`) | N/A |

---

## Orchestration Ownership

To maintain a clean architecture, the following logic should be moved out of repositories and into higher-level components:

| Feature | Orchestrator | Logic Responsibility |
| :--- | :--- | :--- |
| **Artifact Deletion** | `ArtifactCleanupManager` | Coordination between `ArtifactModerationRepository` (Remote) and local DAOs (Room). |
| **Safety Reporting** | `ReportUseCase` | Combining `ArtifactModerationRepository.submitReport` and `ArtifactRepository.evictCache`. |
| **Personalization** | `EngagementCoordinator` | Coordinating `ArtifactInteractionRepository` and `PersonalizationEngine`. |
| **Publication** | `PublishingManager` | Orchestrating Storage upload, Transcript upload, and Firestore document creation. |

---

## Verification Plan

### Automated Tests
- **Unit Tests**: Create `[NewRepository]Test.kt` for each extraction. Port existing tests from `ArtifactRepositoryTest`.
- **Integration Tests**: Verify Hilt module bindings for all new repositories.
- **E2E Tests**: Run `OfflineSyncE2E.kt` after each phase to ensure interaction/sync flows still work.

### Manual Verification
1. **Interaction Sync**: Verify that saving an artifact triggers `InteractionSyncWorker` and reflects in Firestore.
2. **Upload Flow**: Verify resumable upload handles network interruptions (using Airplane mode).
3. **Feed Consistency**: Verify that reporting an artifact immediately hides it from the Home Feed.

---

## Complexity & Risk Estimation

- **Overall Complexity:** High
- **Risk Level:** Medium (Mitigated by phased delegation).
- **Estimated Effort:** 4-6 developer days for full migration and testing.

### Regression Risks
- **Race Conditions**: During delegation, ensure state consistency between the old and new repositories if they share local caches.
- **DI Circular Dependencies**: Be careful when repositories start depending on each other (use `Lazy` or move shared logic to Use Cases).
- **Worker Desync**: `InteractionSyncWorker` must be updated to use the new `ArtifactInteractionRepository`.
