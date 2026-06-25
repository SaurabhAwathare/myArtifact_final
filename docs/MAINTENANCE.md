# Artifact Long-Term Maintenance Guide

This handbook describes how to maintain and evolve the Artifact codebase while preserving its architectural integrity.

---

## Modifying Architecture

### 1. Propose a Change
- Identify the problem.
- Research existing invariants and ADRs.
- Create a new ADR in `docs/adr/` following the template.

### 2. Update Documentation
- Update `docs/architecture/PublishingFlowInvariants.md` if rules change.
- Update `docs/architecture/SystemOwnership.md` if component boundaries shift.
- Update `docs/AI_AGENT_GUIDELINES.md` if new engineering rules are introduced.

### 3. Implement and Verify
- Implement the change in the relevant Repository or Model.
- Add tests to verify the new architectural logic.
- Ensure the project builds successfully with `gradle_build("app:assembleDebug")`.

---

## Common Tasks

### Introducing a New Feature
- **New Lifecycle State**: Add to `ArtifactLifecycle.kt`, update the transition matrix, and update `LifecycleDocumentation.md`.
- **New Repository**: Create the repository in `com.saurabh.artifact.repository`, define its SSOT, and add it to `SystemOwnership.md`.
- **New Worker**: Ensure it only operates on finalized data (Invariant 7) and update `SystemOwnership.md`.

### Deprecating Components
- Mark the component with `@Deprecated`.
- Update the relevant ADR to status `Superseded` or `Deprecated`.
- Create a new ADR explaining the replacement.

### Database Migrations
- Define the migration in `DraftsDatabase.kt`.
- Ensure the SSOT principle is maintained.

---

## Documentation Standards

Every architecture document should include:
- **Purpose**: Why does this exist?
- **Scope**: What does it cover?
- **Responsibilities**: What does it do?
- **Dependencies**: What does it rely on?
- **Source of Truth**: Where is the data stored?
- **Failure Recovery**: How does it handle errors?
- **Design Decisions**: Reference relevant ADRs.
- **Last Updated**: YYYY-MM-DD.
