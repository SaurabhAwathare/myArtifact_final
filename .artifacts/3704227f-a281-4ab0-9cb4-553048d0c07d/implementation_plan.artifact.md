# Cloud Functions Deployment Cleanup

Move standalone utility and debugging scripts from the `functions/` directory to a new `scripts/` directory at the project root to unblock the deployment linting process.

## Proposed Changes

### Root Directory

#### [NEW] [scripts/](file:///F:/Android Project/01/scripts/)
Create a new directory to house non-production utility scripts.

### Functions Directory

#### [MOVE] [dump_db.js](file:///F:/Android Project/01/functions/dump_db.js) -> [scripts/dump_db.js](file:///F:/Android Project/01/scripts/dump_db.js)
#### [MOVE] [test_db.js](file:///F:/Android Project/01/functions/test_db.js) -> [scripts/test_db.js](file:///F:/Android Project/01/scripts/test_db.js)
#### [MOVE] [verify_issue.js](file:///F:/Android Project/01/functions/verify_issue.js) -> [scripts/verify_issue.js](file:///F:/Android Project/01/scripts/verify_issue.js)
#### [MOVE] [test_firestore.js](file:///F:/Android Project/01/functions/test_firestore.js) -> [scripts/test_firestore.js](file:///F:/Android Project/01/scripts/test_firestore.js)
#### [MOVE] [verify_cleanup.js](file:///F:/Android Project/01/functions/verify_cleanup.js) -> [scripts/verify_cleanup.js](file:///F:/Android Project/01/scripts/verify_cleanup.js)
#### [MOVE] [auth_delete_user.js](file:///F:/Android Project/01/functions/auth_delete_user.js) -> [scripts/auth_delete_user.js](file:///F:/Android Project/01/scripts/auth_delete_user.js)
#### [MOVE] [get_one_artifact.js](file:///F:/Android Project/01/functions/get_one_artifact.js) -> [scripts/get_one_artifact.js](file:///F:/Android Project/01/scripts/get_one_artifact.js)
#### [MOVE] [inspect_identity.js](file:///F:/Android Project/01/functions/inspect_identity.js) -> [scripts/inspect_identity.js](file:///F:/Android Project/01/scripts/inspect_identity.js)
#### [MOVE] [populate_test_data.js](file:///F:/Android Project/01/functions/populate_test_data.js) -> [scripts/populate_test_data.js](file:///F:/Android Project/01/scripts/populate_test_data.js)

## Verification Plan

### Automated Tests
1. Run lint in functions directory:
   ```bash
   cd functions
   npm run lint
   ```
   Expect: No errors from the moved utility scripts.

2. Run dry-run or partial deployment:
   ```bash
   firebase deploy --only functions
   ```
   Expect: The process to proceed past the `predeploy` linting stage.

### Manual Verification
1. Confirm the `scripts/` directory exists and contains all moved files.
2. Confirm the `functions/` directory no longer contains these utility scripts.
