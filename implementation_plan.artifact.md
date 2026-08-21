# Artifact — Export Data UX Redesign Implementation Plan

This plan aims to transition the Export Data user experience from a "screen-first" blocking model to a "notification-first" background model. The Settings screen will remain interactive, and the Android notification will become the primary surface for progress and completion.

## User Review Required

> [!IMPORTANT]
> The full-screen loading overlay in the Settings screen will be REMOVED. Users will only see an immediate "Export started" confirmation and then track progress in their notification drawer.

## Proposed Changes

### [Component] Background Service & Notifications

#### [MODIFY] [ExportService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/ExportService.kt)
- Extract the display name of the export ZIP file from the URI using `ContentResolver`.
- Store the filename to include it in the "Export Complete" notification.
- Update notification calls to pass more descriptive information.

#### [MODIFY] [NotificationHelper.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/util/NotificationHelper.kt)
- Update `showExportResultNotification` to optionally include a filename.
- Refine notification titles and text for a "calmer" Artifact tone.
- Ensure the progress notification is the primary focus.

---

### [Component] Settings UI

#### [MODIFY] [SettingsViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/settings/SettingsViewModel.kt)
- Update `exportData` to emit a `ShowMessage` event immediately after starting the service.
- Ensure `isExporting` correctly disables the Export button while the service is active.

#### [MODIFY] [SettingsScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/settings/SettingsScreen.kt)
- REMOVE the `if (isExporting)` centered overlay block.
- Update the "Export Data" button behavior:
    - Keep it disabled while `isExporting` is true.
    - Change its subtitle to "Export currently in progress" while active.
- Handle the `ExportInitiated` (renamed to `ExportStarted` or similar) event to show a Snackbar.

## Verification Plan

### Automated Tests
- N/A (Investigation constrained to UX implementation).

### Manual Verification
1. **Trigger Export**: Tap "Export Data", select a location.
2. **Start Feedback**: Confirm a Snackbar appears saying "Export started. Track progress in notifications."
3. **Non-Blocking UI**: Confirm you can still navigate the Settings screen and toggle other switches while the export runs.
4. **Notification Progress**: Open notification drawer; confirm "Exporting your Artifacts" with item counts (e.g., "4 of 145").
5. **Completion**: Wait for export to finish. Confirm the "Export complete" notification appears with the filename.
6. **Omission Flow**: (If possible) Verify distinct notification for "Export completed with omissions".
