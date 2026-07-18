# Investigation Report: Firestore Security Rules Deployment Mismatch

## Problem Statement
The Firestore Security Rules in the Android Studio project are newer (last modified: July 18, 2026, 17:26) than the rules currently active in the Firebase Console. Despite local changes and successful testing, the deployed rules remain out of sync.

## Question Being Answered
Why are the Firestore Security Rules defined in the project not matching the rules currently active in Firebase?

## Evidence Collected
1.  **Local Rules File:** Located at [firestore.rules](file:///F:/Android Project/01/firestore.rules).
    - Last Modified: **July 18, 2026, 17:26:09** (very recent).
2.  **Configuration:** [firebase.json](file:///F:/Android Project/01/firebase.json) correctly identifies `firestore.rules` as the source of truth for the `(default)` database.
3.  **Active Project:** [.firebaserc](file:///F:/Android Project/01/.firebaserc) confirms the active project is `myartifact-555e3`.
4.  **Deployment Scoping:** [functions/package.json](file:///F:/Android Project/01/functions/package.json) contains a script:
    - `"deploy": "firebase deploy --only functions"`
5.  **Emulator Usage:** [firestore-debug.log](file:///F:/Android Project/01/firestore-debug.log) and logs in `firestore-tests/` confirm extensive recent testing against the local emulator.
6.  **Missing Central Script:** There is no `package.json` or deployment script in the project root to perform a full `firebase deploy`.

## Findings
The root cause of the mismatch is a **scoped deployment workflow**.
- The project is configured such that the primary deployment command known to the environment (residing in the `functions` directory) is explicitly restricted to Firebase Functions using the `--only functions` flag.
- When developers run `npm run deploy` from the `functions` folder, the CLI successfully deploys backend code but **completely skips** the Firestore Security Rules located in the root.
- Because the user has been iterating on rules for local testing (as evidenced by recent timestamps and emulator logs), the local file has advanced while the server has not been updated since the last full or rules-specific deployment.

## Confidence Level
**High (95%)**
The presence of a scoped deploy script and the lack of a root-level deploy script strongly indicates that the standard deployment path used in this project ignores Firestore rules.

## Remaining Unknowns
- Whether the user has previously attempted a full `firebase deploy` from the root and encountered a silent failure (unlikely given the logs).
- The exact content of the rules currently in the Firebase Console (cannot be verified without CLI/Console access).

## Recommended Next Step
To synchronize the rules, a targeted deployment of the rules specifically should be performed from the project root:
```bash
firebase deploy --only firestore:rules
```
Alternatively, a full deployment can be run from the root:
```bash
firebase deploy
```
Going forward, it is recommended to add a deployment script to a root `package.json` that includes both rules and functions to prevent future drift.
