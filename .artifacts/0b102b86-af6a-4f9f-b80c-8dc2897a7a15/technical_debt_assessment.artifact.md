# Post-Migration Technical Debt Assessment: Sigil Refactor

This report evaluates the remaining technical debt following the successful production migration from the human-like "Avatar" system to the abstract "Sigil" identity system.

## 1. Executive Summary
The primary data migration is complete. 100% of historical Artifacts have been normalized to the Sigil schema, and active users are being repaired on-the-fly. However, the codebase still contains significant "scaffolding" required for the transition, as well as legacy terminology and obsolete modeling.

## 2. Legacy Avatar Inventory

### Code Artifacts (High Priority Cleanup)
| Item | Type | Path | Status |
| :--- | :--- | :--- | :--- |
| `AvatarParts.kt` | Model | `model/avatar/AvatarParts.kt` | **Obsolete**. Contains facial features. |
| `AvatarConfig.kt` | File | `model/AvatarConfig.kt` | **Debt**. Contains `SigilConfig` but uses legacy filename. |
| `ui/avatar/` | Directory | `ui/avatar/` | **Terminology**. Should be renamed to `ui/identity/` or `ui/sigil/`. |

### Model Fields (Medium Priority)
| Legacy Field | Modern Replacement | Persistence |
| :--- | :--- | :--- |
| `followersCount` | `resonanceInCount` | Firestore `users` doc |
| `followingCount` | `resonanceOutCount` | Firestore `users` doc |
| `avatarSeed` | `sigilSeed` | DataStore / Firestore |
| `avatarConfig` | `sigilConfig` | DataStore / Firestore |

### Compatibility Logic (Do Not Remove Yet)
- **`ProfileRepairService.kt`**: Fallback mapping from `avatar*` to `sigil*` fields. Required for inactive users.
- **`UserSessionManager.kt`**: Legacy `PreferencesKeys` and migration logic in the `userProfile` flow.
- **Media3 Bundles**: `avatar_seed` key in `PlaybackService.kt`.

## 3. Debt Impact Analysis
- **Maintainability**: New developers may be confused by facial feature enums (`HairType`, `EyeType`) that are never rendered.
- **Code Bloat**: `ProfileRepairService` contains ~100 lines of migration logic that will eventually be dead code.
- **Terminology Drift**: The coexistence of "Avatar" and "Sigil" in the UI package structure weakens the domain model clarity.

## 4. Risks of Premature Cleanup
- **Inactive User Data Loss**: Removing fallbacks before all users have logged in since July 2026 will cause them to lose their visual identity (it will reset to defaults).
- **Serialization Breakage**: While `avatar*` fields are deleted from Firestore docs, the application must still be able to handle "partially clean" docs if any edge cases were missed.

## 5. Retirement Success Criteria
The Avatar system can be declared fully retired when:
1.  **Zero Migration Activity**: `USER_PROFILE_REPAIR_COMPLETED` logs with `cleanup=true` have not appeared in production for 30 consecutive days.
2.  **No String Matches**: A project-wide search for `(?i)avatar` returns zero results in `src/main` (excluding migration logs).
3.  **Schema Convergence**: All `followersCount`/`followingCount` values in Firestore are verified to be zero or migrated to `resonance*`.
