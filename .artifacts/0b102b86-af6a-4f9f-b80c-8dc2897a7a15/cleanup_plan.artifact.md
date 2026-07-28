# Phased Cleanup Plan - Sigil System Transition

A structured approach to retiring legacy identity code while maintaining production stability.

## Phase 1: Terminology & Obsolete Models (Safe Immediate Cleanup)
*Goal: Align the codebase with the "Sigil" terminology and remove unused data structures.*

- [ ] **Rename Model File**: Rename `AvatarConfig.kt` to `SigilConfig.kt`.
- [ ] **Remove Obsolete Models**: Delete `model/avatar/AvatarParts.kt` (Hair, Eyes, Mouth types).
- [ ] **Rename UI Package**: Rename `com.saurabh.artifact.ui.avatar` to `com.saurabh.artifact.ui.identity`.
- [ ] **Refactor Constants**: Update `PlaybackService` bundle keys to use `sigil_seed` (while keeping `avatar_seed` as a deprecated fallback if necessary).

## Phase 2: Functional Simplification (Monitoring Required)
*Goal: Remove "scaffolding" code once most users have been migrated.*

- [ ] **Wait Condition**: Monitor `ProfileRepairService` logs for 30 days. Proceed when repair frequency < 1 per week.
- [ ] **Simplify `ProfileRepairService`**:
    - Remove `sanitizeSigilConfig` v1/v2 logic.
    - Remove `avatar*` fallback mapping in `sanitizeFromMap`.
- [ ] **Cleanup `UserSessionManager`**:
    - Remove `AVATAR_*` PreferencesKeys.
    - Simplify the `userProfile` flow to only read `SIGIL_*` keys.

## Phase 3: Infrastructure & Backend (Long-Term)
*Goal: Finalize the new Resonance schema and retire legacy counters.*

- [ ] **Cloud Functions Cleanup**: Stop updating `followersCount` and `followingCount` in `onFollowIntentCreated`.
- [ ] **Entity Cleanup**:
    - Remove `@ColumnInfo` name overrides for legacy columns (if any).
    - Remove `followersCount` from the Kotlin `User` data class.

## Verification Checklist

### Automated Verification
- [ ] Unit tests in `ProfileRepairServiceTest` must be updated (or some deleted) to reflect the absence of migration paths.
- [ ] `gradle_build("app:assembleDebug")` must complete with zero warnings related to missing fields.

### Manual Verification
- [ ] Launch the app with a fresh install; verify default Sigil is "New Soul".
- [ ] Perform a "Sigil Ritual" (randomization); verify new configuration is persisted to `sigilConfig` correctly.
- [ ] Check the feed; verify both new and historical Artifacts render correctly.
