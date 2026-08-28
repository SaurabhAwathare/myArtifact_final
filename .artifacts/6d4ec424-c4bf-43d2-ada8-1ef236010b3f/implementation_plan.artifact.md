# Phase 6.2.2 — Quiet Relationship & Privacy Hardening Plan

This plan implements two privacy-focused refinements to the relationship model: quieting follow notifications and restricting resonator list visibility.

## User Review Required

> [!IMPORTANT]
> A new notification channel "Connections" (`CHANNEL_ID_RESONANCES`) is being introduced with `IMPORTANCE_LOW`. Users on Android 8.0+ may see this new channel in their system settings.
>
> [!WARNING]
> Resonator and Following lists will become private to the profile owner. Other users will no longer be able to see who follows a specific presence.

## Proposed Changes

### Android App

#### [MODIFY] [NotificationHelper.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/util/NotificationHelper.kt)
- Define `CHANNEL_ID_RESONANCES = "resonances_channel"`.
- Add "Connections" channel to `initNotificationChannels` with `IMPORTANCE_LOW`.
- Update `showInteractionNotification` call sites (via `FCMService` or `MainViewModel`) to use this channel for `PRESENCE_RESONATED`.

#### [MODIFY] [ProfileHeader.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/components/ProfileHeader.kt)
- Disable `onClick` for `StatItem` (following/resonators) when `isSelf` is false.
- Visual hint: Reduce alpha or remove click indication for these items when not viewing self.

### Firebase

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- Update `resonance_in` and `resonance_out` rules to only allow `read` if `isOwner(uid)`.

## Verification Plan

### Automated Tests
- **Firestore Rules**: Verify that reading `/users/{otherUid}/resonance_in` fails for an authenticated user who is not `{otherUid}`.
- **Unit Tests**: Verify `NotificationHelper` initializes the new channel correctly.

### Manual Verification
1. **Follow Notification**: Follow a user from another account. Verify the notification arrives silently (no sound/vibration) and is categorized under "Connections".
2. **Privacy Check**: Open a profile that is not yours. Verify that tapping "Following" or "Resonators" does nothing.
3. **Self Access**: Open your own profile. Verify you can still open your "Following" and "Resonators" lists.
4. **Artifact Reactions**: Verify that "Resonate" reactions on artifacts still send notifications through the "Resonances" (Interactions) channel as before.
