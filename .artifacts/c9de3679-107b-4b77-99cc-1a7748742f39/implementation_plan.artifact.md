# Remove External Account Deletion Mechanism

The goal is to ensure that account deletion can only be initiated from within the authenticated Artifact Android application, as per the "Responsible Anonymity" principle and specific product requirements.

## Proposed Changes

### Web / Public Assets

#### [DELETE] [delete-account.html](file:///F:/Android Project/01/public/delete-account.html)
Remove the functional web-based deletion tool.

#### [MODIFY] [privacy.html](file:///F:/Android Project/01/public/privacy.html)
Update the documentation to remove the link to the web deletion tool and clarify that deletion is done in-app.

#### [MODIFY] [index.html](file:///F:/Android Project/01/public/index.html)
Remove the "Account Deletion" link from the footer.

#### [MODIFY] [sitemap.xml](file:///F:/Android Project/01/public/sitemap.xml)
Remove the `delete-account.html` entry to prevent search engine indexing.

## Verification Plan

### Static Verification
- Verify that `delete-account.html` is removed from the `public/` directory.
- Verify that no remaining files in `public/` link to `delete-account.html`.
- Verify that `privacy.html` correctly instructs users to use the app for deletion.

### Code Integrity Check
- Ensure that `SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsRepository.kt`, and `AuthRepository.kt` are NOT modified and their deletion logic remains intact.
- Ensure that the `onUserDeleted` Cloud Function in `functions/src/index.ts` remains unchanged as it is the authoritative cleanup trigger for both in-app and (now removed) external deletion.
