# Legacy Avatar Reference Report

This report identifies all remaining references to "Avatar" terminology and data structures within the `src/main` directory. Every reference has been classified to ensure its retention is intentional during Phase 1 cleanup.

## 1. Summary of References

| Category | Count | Primary Impacted Area |
| :--- | :--- | :--- |
| **Required compatibility** | 12 | Persistence (Firestore/DataStore) & External APIs (Media3) |
| **Scheduled for Phase 1 removal** | 22 | Packages, Filenames, Comments, & Obsolete Models |
| **Scheduled for Phase 2 removal** | 8 | Functional migration logic in Repair services |
| **Unexpected reference** | 0 | None identified |

---

## 2. Legacy Avatar Inventory & Classification

### Required Compatibility
*These references must be retained to avoid data loss for inactive users or to maintain compatibility with external systems.*

| Item | Location | Classification | Reason |
| :--- | :--- | :--- | :--- |
| `"avatar_seed"` | `audio/PlaybackService.kt` | Required | Metadata key for active notifications & Media3 controllers. |
| `"avatar_seed"` | `audio/PlaybackSessionManager.kt` | Required | Consistency with `PlaybackService`. |
| `AVATAR_SEED` | `data/local/UserSessionManager.kt` | Required | DataStore key for migrating local identities of inactive users. |
| `AVATAR_COLOR` | `data/local/UserSessionManager.kt` | Required | DataStore key for migrating local identities of inactive users. |
| `AVATAR_CONFIG_JSON` | `data/local/UserSessionManager.kt` | Required | DataStore key for migrating local identities of inactive users. |
| `"avatarSeed"` | `repository/UserRepository.kt` | Required | Target string for Firestore `FieldValue.delete()`. |
| `"avatarColor"` | `repository/UserRepository.kt` | Required | Target string for Firestore `FieldValue.delete()`. |
| `"avatarConfig"` | `repository/UserRepository.kt` | Required | Target string for Firestore `FieldValue.delete()`. |
| `"AVATAR_UPDATED"` | `ui/util/NotificationMapper.kt` | Required | Mapping for historical notification types in Firestore. |

### Scheduled for Phase 1 Removal (Immediate Cleanup)
*These items will be renamed, deleted, or updated during the current phase.*

| Item | Location | Action |
| :--- | :--- | :--- |
| `AvatarConfig.kt` | `model/` (filename) | **Rename** to `SigilConfig.kt`. |
| `AvatarParts.kt` | `model/avatar/` (file) | **Delete**. Facial feature enums are obsolete. |
| `com...ui.avatar` | Multiple (packages) | **Rename** to `com...ui.sigil`. |
| `com...model.avatar` | Multiple (packages) | **Rename** to `com...model.sigil`. |
| `// ... avatar` | `ui/player/ImmersivePlayerScreen.kt` | **Update** comment to use "Sigil". |
| `[ Avatar ]` | `ui/profile/components/ProfileHeader.kt` | **Update** UI documentation to use "Sigil". |

### Scheduled for Phase 2 Removal (Functional Simplification)
*These references reside within logic that will be retired once the production monitoring period (30 days) is complete.*

| Item | Location | Classification |
| :--- | :--- | :--- |
| `hasLegacyFields` check | `domain/auth/ProfileRepairService.kt` | Phase 2 |
| `avatar*` fallbacks | `domain/auth/ProfileRepairService.kt` | Phase 2 |
| `MIGRATED_LEGACY_AVATAR...` | `domain/auth/ProfileRepairService.kt` | Phase 2 (Diagnostic strings) |

---

## 3. Risk Assessment
- **Package Renaming**: Minimal risk, handled by IDE refactoring tools. Ensures domain model consistency.
- **Model Deletion**: Zero risk. `AvatarParts.kt` has been verified to have no call sites in production code.
- **Media3 Keys**: Risk of broken identity on some Wear OS / Automotive controllers if `avatar_seed` were removed now. Mitigated by keeping it as a secondary key.

## 4. Declaration of Retirement
The "Avatar" terminology can be considered officially retired from the **codebase** once the "Scheduled for Phase 1" items are resolved. The terminology will remain only in **legacy data paths** until Phase 2 and 3.
