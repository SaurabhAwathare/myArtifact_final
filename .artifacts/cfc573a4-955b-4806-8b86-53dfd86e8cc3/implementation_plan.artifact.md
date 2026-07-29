# Artifact Development Reset Plan (Sigil-Only Architecture)

Objective: Transition the project to a clean, modern data architecture by abandoning the legacy migration effort, resetting the development database, and stripping all Avatar-specific compatibility code before v1.0.

## User Review Required

> [!IMPORTANT]
> **Action Required**: Before I begin the code cleanup, you must manually delete the Firestore collections listed in Phase 1. This ensures that the new clean code never encounters legacy data during verification.

## Phase 1: Database Purge & Verification

1.  **Purge**: Manually delete the following collections in the Firebase Console:
    - `users`, `artifacts`, `comments`, `notifications`, `artifact_reactions`, `artifact_reaction_counts`.
2.  **Verify**: Confirm that these collections are empty (0 documents) before proceeding to code changes.
3.  **Baseline Build**: Build and run the app against the empty database to ensure stability.

## Phase 2: Code Cleanup (Avatar-Specific ONLY)

Remove only the code that exists solely to support the Avatar → Sigil transition.

### [Backend] Removal of Migration Tools
- **[DELETE]** `migrate_sigils.js`, `MIGRATION_GUIDE.md`, `backup_verification_report.json`.
- **KEEP**: `scripts/package.json` and `scripts/.gitignore` for future maintenance needs.

### [Android] Stripping Compatibility Logic
- **[DELETE]** `ProfileRepairService.kt` and `ProfileRepairServiceTest.kt`.
- **[MODIFY]** `UserRepository.kt`: Remove repair dependency and legacy transaction blocks.
- **[MODIFY]** `UserSessionManager.kt`: Remove legacy `AVATAR_*` preference keys and fallback logic.
- **[MODIFY]** `IdentitySyncWorker.kt`: Remove `FieldValue.delete()` for legacy `avatar*` fields.

## Phase 3: Repopulation and E2E Validation

1.  **Repopulate**: Run `populate_test_data.js` to rebuild a clean environment.
2.  **E2E Validation**:
    - Register a brand-new user in the app.
    - Publish a new Artifact.
    - Add a comment and a Resonance interaction.
    - Inspect Firestore documents.
    - **Success Criteria**:
        - Every document contains *only* modern `Sigil` fields; no `avatar*` fields exist.
        - No runtime warnings reference Avatar fields.
        - No compatibility or repair services are invoked.

## Phase 4: Final Cleanup and Housekeeping

Once the system is verified stable and the new schema is confirmed:
- **[DELETE]** `migrate_sigils.js`, `MIGRATION_GUIDE.md`, `backup_verification_report.json`.
- **Remove** unused imports across all modified files.
- **Remove** redundant DI bindings for `ProfileRepairService`.
- **Clean up** any unused test resources.

---

## Recommended Execution Order

1.  **User Action**: Manually purge Firestore collections.
2.  **Verification**: Confirm DB is empty and app builds/runs against empty state.
3.  **Core Cleanup**: Delete `ProfileRepairService` and associated tests.
4.  **Integration Cleanup**: Simplify Repositories and Workers.
5.  **Build/Verify**: Ensure app compiles and runs against the clean schema.
6.  **Repopulate**: Run `populate_test_data.js`.
7.  **Final Validation**: Perform detailed E2E check and schema purity inspection.
8.  **Backend Cleanup**: Delete migration-specific scripts and documentation.
9.  **Housekeeping**: Final dead-code removal and DI cleanup.

## Confidence Level: Level 4 (Aligned with Development Reset Strategy)
