# Deploy Account Deletion Cloud Functions

This plan outlines the deployment of the Cloud Functions required for the Account Deletion feature to the Firebase project `myartifact-555e3`. The primary objective is to deploy the `onUserDeleted` trigger along with existing functions and verify successful deployment.

## User Review Required

> [!NOTE]
> This is a deployment-only task. No business logic modifications are planned unless deployment issues arise.

## Proposed Changes

### Cloud Functions

The functions project has been verified to build correctly and includes the `onUserDeleted` trigger in `functions/src/index.ts`.

#### [index.ts](file:///F:/Android%20Project/01/functions/src/index.ts)

- Already contains `onUserDeleted` trigger.
- Verified to export `onUserDeleted` in compiled `lib/index.js`.

---

## Verification Plan

### Automated Tests
- `npm run build` in the `functions` directory to ensure no compilation errors.
- `npx firebase-tools functions:list` after deployment to verify presence of all functions.

### Manual Verification
1. **Verify Local Build**: Confirm `functions/lib/index.js` exists and exports `onUserDeleted`.
2. **Verify Firebase Project**: Confirm `myartifact-555e3` is the active project.
3. **Deploy Functions**: Run `npx firebase deploy --only functions` from the project root.
4. **Post-Deployment Check**:
    - Verify `onUserDeleted` appears in the deployed function list.
    - Confirm existing functions (`onReplyCreated`, `onEngagementUpdated`, `onArtifactDeleted`, etc.) are preserved.
5. **Smoke Check**: Inspect function metadata via Firebase CLI to ensure trigger type and region are correct.
