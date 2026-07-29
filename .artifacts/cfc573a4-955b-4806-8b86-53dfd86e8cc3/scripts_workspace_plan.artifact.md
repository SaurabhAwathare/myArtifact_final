# Artifact Scripts Workspace Initialization Plan

Objective: Initialize a dedicated, encapsulated Node.js environment within the `scripts/` directory to manage operational utilities safely and maintain project-level architectural consistency.

## Required Files

1.  **[NEW] [package.json](file:///F:/Android Project/01/scripts/package.json)**: The core configuration for the workspace.
2.  **[NEW] [.gitignore](file:///F:/Android Project/01/scripts/.gitignore)**: To exclude local `node_modules` and execution artifacts (like logs) from version control.
3.  **[MODIFY] [MIGRATION_GUIDE.md](file:///F:/Android Project/01/scripts/MIGRATION_GUIDE.md)**: Add workspace setup instructions to the existing guide.

## Required Dependencies

All required dependencies are for administrative/operational tasks.

| Dependency | Purpose | Type |
| :--- | :--- | :--- |
| `firebase-admin` | Database access and privileged operations | Production |
| `@google-cloud/firestore` | Specifically for Managed Export (Backup) API | Production |
| `minimist` | Argument parsing for `firestore_backup_manager.js` | Production |

## Proposed `package.json` Structure

```json
{
  "name": "artifact-maintenance-scripts",
  "version": "1.0.0",
  "description": "Administrative and maintenance utilities for the Artifact project.",
  "private": true,
  "main": "index.js",
  "engines": {
    "node": "22"
  },
  "scripts": {
    "migrate:sigils": "node migrate_sigils.js",
    "backup:firestore": "node firestore_backup_manager.js",
    "cleanup:notifications": "node cleanup_notifications.js"
  },
  "dependencies": {
    "@google-cloud/firestore": "^7.10.0",
    "firebase-admin": "^13.0.0",
    "minimist": "^1.2.8"
  }
}
```

## Recommended Node Version
- **Version**: **22.x**
- **Reason**: Matches the `functions/` environment, ensuring developers don't need to juggle multiple Node runtimes and maintaining compatibility with the latest `firebase-admin` SDK features.

## Environment Requirements

1.  **Authentication**: All scripts in this workspace require the `GOOGLE_APPLICATION_CREDENTIALS` environment variable to be set to a service account JSON key with `Firebase Admin` and `Storage Admin` permissions.
2.  **Project Context**: The `projectId` is currently hardcoded in scripts as `myartifact-555e3`. Developers should verify this matches their environment or use Firebase CLI to set the active project.
3.  **Initialization Command**:
    ```bash
    cd scripts
    npm install
    ```

## Confidence Level: Level 4 (Verified by Script Analysis)
