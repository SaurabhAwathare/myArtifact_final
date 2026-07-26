# Firestore Field Ownership & Write Responsibility Audit Plan

Objective: Perform a comprehensive READ-ONLY static investigation to verify ownership, lifecycle, and write responsibility for every Firestore field used in the Artifact application.

## User Review Required

> [!IMPORTANT]
> This is a **READ-ONLY** investigation. No production code will be modified.
> **Status**: APPROVED by User

> [!WARNING]
> Several models (e.g., `Artifact`, `User`) use manual mapping in repositories instead of direct object serialization. This increases the risk of "Silent Data Loss" if new fields are added to the Kotlin models but omitted from the mapping methods.

## Proposed Investigation Phases

### Phase 1 – Complete Field Ownership Audit
Audit all properties in `Artifact`, `User`, `Comment`, `Reaction`, and `AuthorSnapshot`. Determine the owner (Client, Server, Cloud Function) and requirement level.

### Phase 2 – Complete Write Lifecycle Audit
Map the creation, update, and deletion paths for every field. Identify immutable vs. mutable fields.

### Phase 3 – Firestore Write Matrix
Create a detailed matrix mapping Kotlin fields to Firestore keys, identifying missing mappings and risk levels.

### Phase 4 – Silent Data Loss Verification
Verify previously reported data loss issues (e.g., `toxicityScore`, `safetyConcernCount`, `reporterIds` in `Artifact`) by checking if they are written by any component.

### Phase 5 – Repository Ownership Audit
Identify which repositories own which collections and if there are any architectural boundary violations.

### Phase 6 – Schema Evolution Audit
Classify fields as Active, Legacy, Future, or Server-generated.

### Phase 7 – Manual Mapping Validation
Audit manual mapping methods like `mapArtifactToFirestoreData` and `mapUserToLocal`.

### Phase 8 – Field Authority & Single Source of Truth Audit
For every Firestore field, determine:
* Which component is the authoritative owner?
* Is there exactly one authoritative writer?
* Can multiple components modify the same field?
* Are there conflicting update paths?
* Does any repository bypass the intended architecture?
* Does the field violate the Single Source of Truth principle?
* Could concurrent writers overwrite each other's values?
* Are transactions required but not consistently used?

Produce a **Field Authority Matrix** containing:
| Field | Authoritative Owner | Other Writers | Conflict Risk | SSOT Status |
| ----- | ------------------- | ------------- | ------------- | ----------- |

Classify every field as:
* ✅ Single Source of Truth maintained
* ⚠ Multiple writers (acceptable)
* ❌ Multiple writers (architectural risk)
* ❓ Ownership unclear

## Verification Plan

### Static Code Analysis
- Trace all Firestore `set`, `update`, and `runTransaction` calls.
- Compare Kotlin model definitions with manual mapping logic.
- Verify `@PropertyName` and `@Exclude` annotations.

### Manual Verification
- None (Read-only investigation).

## Deliverables
- A structured investigation report (`investigation_report.artifact.md`).
- Verified findings ranked by production risk.
