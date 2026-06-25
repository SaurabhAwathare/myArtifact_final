# Architecture Change Checklist

Use this checklist before merging any changes that modify the system's architecture, data flow, or core models.

---

## Pre-Merge Verification

- [ ] **Invariants Preserved**: Does this change violate any of the [Publishing Flow Invariants](PublishingFlowInvariants.md)?
- [ ] **SSOT Maintained**: Is Room still the only authoritative source of truth for persisted data?
- [ ] **Repository Ownership**: Is the business logic residing in the appropriate Repository rather than the ViewModel or Service?
- [ ] **ADR Required?**: If this is a significant design decision, has a new [ADR](../adr/) been created?
- [ ] **Documentation Updated**: Have the relevant `.md` files in `docs/architecture/` been updated?
- [ ] **Lifecycle Integrity**: If a new state was added, is the transition matrix in `ArtifactLifecycle` updated and documented?
- [ ] **Recovery Paths**: Is the change compatible with existing recovery workers?
- [ ] **Atomicity & Idempotency**: Are write operations wrapped in transactions? Are they safe to call multiple times?
- [ ] **AI Guidance**: Does the [AI_AGENT_GUIDELINES.md](../AI_AGENT_GUIDELINES.md) need to be updated to account for this change?
- [ ] **Tests Updated**: Are there new unit or integration tests verifying this architectural change?

---

## Documentation Quality Standard

Every updated architecture document must include:
- Purpose & Scope
- Responsibilities & Dependencies
- Source of Truth
- Failure Recovery logic
- Links to related ADRs

*Last Updated: 2026-06-25*
