# Artifact Publishing Flow Architecture Invariants

This document defines the permanent architectural contract for the Artifact Publishing Flow. These invariants are rules that must always remain true, regardless of future refactoring or feature additions.

---

## Core Invariants

### Invariant 1: Room is the authoritative persisted state.
* Room is the only persisted source of truth.
* UI never permanently owns persisted data.
* Memory state is temporary.

### Invariant 2: Repository owns business transactions.
* Business orchestration belongs in Repository.
* DAOs perform persistence only.
* Services coordinate operations but do not implement persistence rules.

### Invariant 3: Recording finalization is atomic.
Recording completion updates (`durationMs`, `durableBytes`, `lifecycle`, `updatedAt`) must always occur inside one transaction. No observer should ever see a partially finalized draft.

### Invariant 4: Recording finalization is idempotent.
Calling `finalizeRecording()` multiple times must never duplicate work, regress lifecycle, overwrite newer data, or corrupt metadata.

### Invariant 5: Lifecycle transitions are explicit.
Every lifecycle transition must exist in the approved transition matrix. No ordinal comparisons. No implicit transitions.

### Invariant 6: COMPLETED is emitted only after persistence succeeds.
The completion sequence is always:
Finalize File → Repository Transaction → Room Commit → Worker Enqueue → COMPLETED → Navigation.

### Invariant 7: Workers never operate on partially finalized drafts.
Processing workers should only begin after recording is finalized, lifecycle updated, and metadata committed.

### Invariant 8: UI buffers are temporary.
Temporary buffers (e.g., `_titleInput`) must improve responsiveness but never permanently override Room, and must relinquish control after persistence.

### Invariant 9: Every persisted field has one authoritative writer.
No persisted field should have multiple competing owners.

| Field | Owner |
| --- | --- |
| durationMs | RecordingRepository |
| lifecycle | RecordingRepository |
| title | PublishingStudioViewModel |
| emotion | PublishingStudioViewModel |
| reviewCompleted | ReviewSessionManager |
| uploadStatus | PublishingOrchestrator |

### Invariant 10: Recovery never corrupts data.
Recovery must never regress lifecycle, duplicate processing, or overwrite newer metadata.

---

## Architecture Compliance Checklist

Every pull request touching the publishing flow should verify:

- [ ] Room remains the authoritative persisted state.
- [ ] Repository still owns business transactions.
- [ ] Atomic finalization preserved.
- [ ] Idempotency preserved.
- [ ] Lifecycle matrix updated if new states are added.
- [ ] Workers still operate only on finalized drafts.
- [ ] UI buffers remain temporary.
- [ ] Single Source of Truth preserved.
- [ ] Recovery paths remain safe.
- [ ] Documentation updated if architecture changed.

---

## Related Documents
- **[Architecture Decision Records](../adr/)**: ADR-0001, ADR-0003, ADR-0004, ADR-0005, ADR-0006.
- **[System Ownership](SystemOwnership.md)**
- **[AI Agent Guidelines](../AI_AGENT_GUIDELINES.md)**
- **[Architecture Index](README.md)**

*Last Updated: 2026-06-25*
