# Sigil System Migration - Final Fixes (Revised)

This plan addresses the remaining legacy `Avatar` references following the Sigil System Refactor, specifically fixing the compilation error in `PlaybackService.kt`.

## User Review Required

> [!IMPORTANT]
> **Serialized Compatibility**: To ensure reliability and avoid breaking active notifications or deep links, **bundle keys will NOT be renamed**. The string value `"avatar_seed"` will be preserved even as the underlying model properties are updated to `sigilSeed`.

## Proposed Changes

### Audio System

#### [MODIFY] [PlaybackService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackService.kt)
- Update property access: `artifact.author.avatarSeed` → `artifact.author.sigilSeed`.
- **Constraint**: Maintain the bundle key as `"avatar_seed"`.

#### [MODIFY] [PlaybackSessionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackSessionManager.kt)
- Update property access: `artifact.author.avatarSeed` → `artifact.author.sigilSeed`.
- **Constraint**: Maintain the bundle key as `"avatar_seed"`.

### UI Components

#### [MODIFY] [ImmersivePlayerScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ImmersivePlayerScreen.kt)
- Replace `AvatarConfig` with `SigilConfig`.
- Replace `avatarSeed` with `sigilSeed`.
- Replace `ArtifactAvatar` with `ArtifactSigil`.
- Fix logic using `playableArtifact?.avatarSeed` to use `sigilSeed`.

#### [MODIFY] [PresenceItem.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/PresenceItem.kt)
- Replace `user.avatarConfig` with `user.sigilConfig`.
- Replace `ArtifactAvatar` with `ArtifactSigil`.

### Tests

#### [MODIFY] [UserIdentityValidatorTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/auth/UserIdentityValidatorTest.kt)
- Update `healthyUser` to use `sigilSeed` and `sigilConfig`.
- Update error reason strings (e.g., `MISSING_AVATAR_SEED` → `MISSING_SIGIL_SEED`) to match the updated validator logic.

#### [MODIFY] [UserSessionManagerTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/data/local/UserSessionManagerTest.kt)
- Update to use `SigilConfig` and `sigilSeed`.
- Update `updateAvatarConfig` call to `updateSigilConfig`.

### File & Model Cleanup

#### [DELETE] [AvatarConfig.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/AvatarConfig.kt)
- This file is now redundant as `SigilConfig.kt` (already present) contains the correct model.

## Verification Plan

### Automated Tests
- Run `UserIdentityValidatorTest`.
- Run `UserSessionManagerTest`.
- Build the `:app` module to ensure all unresolved references are resolved.

### Manual Verification
- **Semantic Audit**: Run "Find Usages" on the following legacy symbols and verify that every remaining occurrence is either for Room compatibility, intentional migration logic, or serialized storage:
    - `AvatarConfig`
    - `avatarSeed`
    - `avatarConfig`
    - `ArtifactAvatar`
    - `AvatarRenderer`
    - `CartoonRenderer`
- **Text Search**: Perform a project-wide search for "avatar" to catch any missed strings or comments that should be migrated.
