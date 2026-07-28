# Phase 1 Cleanup: Terminology & Obsolete Models

Align the codebase with the "Sigil" terminology and remove clearly obsolete data structures while maintaining production stability and backward compatibility for inactive users.

## User Review Required

> [!IMPORTANT]
> **Media3 Bundle Keys**: I am introducing `sigil_seed` as the canonical key for Media3 metadata while retaining `avatar_seed` for backward compatibility with active notifications/external controllers.

## Proposed Changes

### [Component] Domain Models
*Normalization of the core identity data structures.*

#### [MODIFY] [AvatarConfig.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/AvatarConfig.kt) -> [SigilConfig.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/SigilConfig.kt)
- **Rename** file to match the class name `SigilConfig`.
- Update KDoc to explicitly mention that this replaced the legacy Avatar system.

#### [RENAME] `com.saurabh.artifact.model.avatar` -> `com.saurabh.artifact.model.sigil`
- Rename the package and directory.
- Affects: `SigilEnums.kt`.
- Update all imports across the project.

#### [DELETE] [AvatarParts.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/avatar/AvatarParts.kt)
- Remove `HairType`, `EyeType`, `MouthType`, `FaceShape`, and `AccessoryType`.
- **Reason**: These were verified to have zero production usages following the refactor to geometric sigils.

---

### [Component] UI Architecture
*Structural alignment of identity-related UI components.*

#### [RENAME] `com.saurabh.artifact.ui.avatar` -> `com.saurabh.artifact.ui.sigil`
- Rename the package and directory.
- Affects: `SigilRitualScreen.kt`, `SigilViewModel.kt`, and the `renderer/` sub-package.
- Update all imports across the project (approx. 15 files).

---

### [Component] Audio & Media3
*Updating metadata keys for external consumers.*

#### [MODIFY] [PlaybackService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackService.kt) and [PlaybackSessionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackSessionManager.kt)
- Update `createMediaItem` metadata extras:
    ```kotlin
    android.os.Bundle().apply {
        putString("author_sigil", artifact.author.sigil)
        putString("sigil_seed", artifact.author.sigilSeed)  // Canonical
        putString("avatar_seed", artifact.author.sigilSeed) // Legacy Support
    }
    ```

---

### [Component] Compatibility Scaffolding
*Clarifying the role of migration code.*

#### [MODIFY] [ProfileRepairService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/ProfileRepairService.kt) and [UserSessionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/UserSessionManager.kt)
- Update internal variable names and comments to clearly demarcate "Legacy Avatar" code paths vs "Modern Sigil" paths.
- Ensure `hasLegacyFields` logic remains untouched for inactive users.

## Verification Plan

### Automated Tests
- Run `ProfileRepairServiceTest` to ensure renaming didn't break migration logic.
- Run `PlaybackServiceTest` (if exists) or verify MediaItem creation logic.
- `gradle_build("app:assembleDebug")` to verify no broken references.

### Manual Verification
- Launch the app and navigate to the "Sigil Ritual" (formerly Avatar Editor).
- Verify that Sigil generation and saving still work perfectly.
- Play an artifact and verify that the notification (if it uses extras) displays the correct identity.
