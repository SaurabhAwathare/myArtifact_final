# Artifact Sigil Migration Operational Guide (v1.0.0)

This guide details the procedure for restoring visual identity to historical Artifacts by migrating legacy avatar fields to the Sigil system.

## ⚠️ Critical Warning
Once legacy `author.avatar*` fields are deleted, they cannot be restored without a full Firestore database restoration. **Always perform a backup before execution.**

---

## 0. Workspace Setup
Before running any scripts, initialize the local environment:
1.  **Change Directory**:
    ```bash
    cd scripts
    ```
2.  **Install Dependencies**:
    ```bash
    npm install
    ```
3.  **Authentication**:
    Set the `GOOGLE_APPLICATION_CREDENTIALS` environment variable to the absolute path of your service account JSON key.
    - *Windows*: `$env:GOOGLE_APPLICATION_CREDENTIALS="C:\path\to\key.json"`
    - *macOS/Linux*: `export GOOGLE_APPLICATION_CREDENTIALS="/path/to/key.json"`
4.  **Project Verification**:
    Verify that the `projectId` in `migrate_sigils.js` and `firestore_backup_manager.js` matches your active Firebase project.

---

## 1. Pre-Migration Checklist
- [ ] **Firestore Backup**: Perform a full database export.
    - Run: `node scripts/firestore_backup_manager.js --action=backup`
    - Monitor: `node scripts/firestore_backup_manager.js --action=status --operation="[OP_NAME]"`
    - Verify: `node scripts/firestore_backup_manager.js --action=verify --path="gs://..."`
- [ ] **Service Account**: Ensure `GOOGLE_APPLICATION_CREDENTIALS` is set to a JSON key with `Cloud Datastore Owner` or `Firebase Admin` permissions.
- [ ] **Environment**: Verify the target Project ID in `migrate_sigils.js` (default: `myartifact-555e3`).

---

## 2. Workflow

### Phase A: Dry Run (Safe)
Execute the script without the `--execute` flag to calculate the migration scope.
```bash
node scripts/migrate_sigils.js
```
- **Review**: Check the "Docs Migrated" count. This represents how many documents will be updated.
- **Verification**: If possible, manually check a few document IDs in the Firestore Console to confirm they possess the `author.avatarSeed` field.

### Phase B: Execution (Writes)
Execute the script with the `--execute` flag.
```bash
node scripts/migrate_sigils.js --execute
```
- **Prompt**: You must type `MIGRATE` when prompted to confirm.
- **Batching**: The script processes 500 documents per batch. If it fails midway, it is **safe to restart**; it will skip already migrated documents.

---

## 3. Post-Migration Verification
The script performs a random sampling verification pass automatically after completion.
1.  Check the "Verification Results" in the console output.
2.  Log in to the app and scroll to older artifacts in the feed.
3.  Verify that their colors and patterns match their original identity (not defaults).

---

## 4. Recovery & Rollback

### If Migration Fails Midway
The script is idempotent. Simply run it again. It will query for documents that still have `author.avatarSeed` and resume.

### Rollback Strategy
There is **no localized rollback** for the deletion of `avatar*` fields.
- **Disaster Recovery**: Restore the entire database from the backup performed in Section 1.
- **Targeted Recovery**: Use the `migration_failures.log.json` to identify specific documents that failed and correct them manually or via a targeted script.

---

## 5. Performance Estimates
*Based on average Firestore write latencies.*

| Collection Size | Estimated Runtime |
| :--- | :--- |
| 1,000 Artifacts | ~30 seconds |
| 10,000 Artifacts | ~3-5 minutes |
| 100,000 Artifacts | ~20-30 minutes |

---

## 6. Failure Log Schema
Failures are written to `scripts/migration_failures.log.json`:
```json
{
  "id": "artifact_abc_123",
  "reason": "Missing avatarSeed field",
  "timestamp": "2026-07-28T18:00:00Z",
  "retryEligible": true
}
```
