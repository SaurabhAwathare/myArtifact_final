# Creator Profile Published Artifact Resolution — Refined Implementation Plan

Refining the Creator Profile published artifact resolution architecture to strictly enforce Responsible Anonymity invariants and prevent Firebase Auth UIDs from being used as public `author.anonymousId` query parameters.

## Problem
In the public Creator Profile flow, querying for published artifacts was susceptible to passing a Firebase Auth UID as the query parameter for `author.anonymousId` (e.g., `artifacts.whereEqualTo("author.anonymousId", firebaseAuthUid)`).

Because public artifacts in Firestore store anonymous persona IDs (such as `usr_123`) in `author.anonymousId` and NOT Firebase Auth UIDs, executing a Firestore query using a Firebase Auth UID is both:
1. Technically incorrect (returns zero artifacts or mismatched data).
2. A critical privacy violation (exposing or querying by account-level Firebase Auth UIDs in public queries).

Furthermore, in `ProfileViewModel.kt`'s `loadMorePublished()`, pagination fell back to `_targetUserId.value` or `currentUserId`, which could trigger public artifact queries using Firebase Auth UIDs during pagination when viewing another creator's profile.

## Root Cause
1. **Conflation in `GetProfileDataUseCase.kt`**:
   The use case previously defined:
   ```kotlin
   val profileLookupId = if (isSelf) currentUserId else (targetPersonaId ?: targetUserId ?: "")
   val artifactQueryId = if (isSelf) currentUserId else (targetPersonaId ?: targetUserId ?: "")
   ```
   For non-self views, `artifactQueryId` fell back to `targetUserId` (a Firebase Auth UID) if `targetPersonaId` was null/blank.

2. **Unsafe Fallback in `ProfileViewModel.kt`**:
   `loadMorePublished()` resolved the pagination query target using:
   ```kotlin
   val userId = _targetUserId.value ?: _targetPersonaId.value ?: currentUserId ?: return
   ```
   When `_targetPersonaId.value` was null, this passed `_targetUserId.value` (Firebase Auth UID) into `artifactRepository.getUserArtifactsPage()`, which queried `author.anonymousId` with a Firebase Auth UID.

3. **Ambiguity in Navigation & Resolution**:
   Components like `GlobalOverlayHost` or deep links might navigate using `Profile(userId = uid)` when only the Firebase UID is known at navigation time (e.g., from notification actors or user lookups). However, the profile loading layer did not resolve `userId` -> `profile.anonymousId` BEFORE executing the public artifact query, leading to direct UID queries.

## Corrected Minimal Fix

### Architectural Invariant
For a public Creator Profile:
- **Public Artifact Query Key MUST ALWAYS be an anonymous persona ID (`author.anonymousId`).**
- **The query `artifacts.whereEqualTo("author.anonymousId", ...)` MUST NEVER receive a Firebase Auth UID.**
- A Firebase Auth UID may be used **ONLY** as an internal identifier to resolve the Creator's User/Profile document (`users/{uid}`) and obtain that Creator's `anonymousId`.

### Corrected Data Flow
```
1. Target Input Provided:
   ├── A. targetPersonaId exists & non-blank
   │      └── Use targetPersonaId DIRECTLY as publicArtifactQueryId
   │
   └── B. targetPersonaId absent BUT targetUserId exists
          └── Step B1: Use targetUserId ONLY to stream target Creator's user profile doc (users/{uid})
          └── Step B2: Extract targetProfile.anonymousId
          └── Step B3: If targetProfile.anonymousId is valid -> Use as publicArtifactQueryId
                       If targetProfile.anonymousId is missing/blank -> publicArtifactQueryId = "" (Query CANNOT run, return empty state)

2. Public Query Execution:
   ├── Self View (isSelf == true):
   │   └── Query strictly from user's private ownership registry (users/{uid}/private/published_artifacts/artifacts)
   │
   └── Other Creator View (isSelf == false):
       └── Query Firestore artifacts collection: whereEqualTo("author.anonymousId", publicArtifactQueryId)
           ★ GUARANTEE: publicArtifactQueryId is ALWAYS a persona ID (e.g. "usr_..."), NEVER a Firebase Auth UID.
```

## Public Persona ID Resolution Rules
1. **Self Detection**:
   `isSelf = (targetUserId == null && targetPersonaId == null) || (targetUserId != null && targetUserId == currentUserId) || (targetPersonaId != null && targetPersonaId.isNotBlank() && targetPersonaId == currentPersonaId)`
2. **Profile Lookup ID** (for fetching profile details):
   For Self: `currentUserId`
   For Other: `targetPersonaId?.ifBlank { null } ?: targetUserId?.ifBlank { null } ?: ""`
3. **Resolved Persona ID**:
   For Self: `currentPersonaId`
   For Other: `targetPersonaId?.ifBlank { null } ?: targetProfile?.anonymousId?.ifBlank { null }`
4. **Public Artifact Query ID**:
   For Self: `currentUserId` (internal key routed to private ownership subcollection in `ArtifactRepository`)
   For Other: `resolvedPersonaId` ONLY. If `resolvedPersonaId` is null/blank, `publicArtifactQueryId` is set to `""`, resulting in a safe empty list.
   **STRICT FORBIDDEN FALLBACK**: Never use `targetPersonaId ?: profile.anonymousId ?: targetUserId` for public artifact queries.

## Firebase UID Safety Rule
- **No Query Leak**: `whereEqualTo("author.anonymousId", firebaseAuthUid)` must NEVER execute under any circumstance.
- **Strict Boundary**: Firebase Auth UIDs are account credentials used solely for authentication and account-level profile document lookup (`users/{uid}`). Public discovery queries strictly use `author.anonymousId`.

## Navigation Architecture & FeedNavigation Explanation
### Why `userId` Route Can Exist
The navigation route `Profile(userId = ...)` exists to handle cases where an incoming trigger provides a Firebase Auth UID rather than a persona ID:
- Notification events where `actorId` is stored as Firebase Auth UID.
- Deep links or user list lookups.
- Internal account navigation.

### Where `userId` Conversion Occurs
When `Profile(userId = firebaseUid)` is launched:
1. `ProfileViewModel` receives `profileRoute.userId` (`_targetUserId`) and `profileRoute.personaId` (`_targetPersonaId` = null).
2. `ProfileViewModel` passes `_targetUserId.value` and `_targetPersonaId.value` to `GetProfileDataUseCase`.
3. `GetProfileDataUseCase` uses `_targetUserId` (`profileLookupId`) ONLY to fetch `userRepository.streamUserProfile(profileLookupId)`.
4. Once `targetProfile` is emitted, `GetProfileDataUseCase` extracts `targetProfile.anonymousId` (`resolvedPersonaId`).
5. `GetProfileDataUseCase` then passes `resolvedPersonaId` as the `userId` argument to `artifactRepository.getUserArtifacts()`.
6. Therefore, the conversion from `userId` to `anonymousId` happens **reactively inside `GetProfileDataUseCase` BEFORE any public Artifact query is initiated.**

### Originating Artifact Preference
If `author.anonymousId` is available on an originating artifact (e.g. `ArtifactCard`, `PlayerViewModel`, `MiniPlayer`, `ImmersivePlayerScreen`), navigation MUST prefer `Profile(personaId = artifact.author.anonymousId)`.

## Safe Failure Behavior
When persona resolution fails (e.g., `targetPersonaId` is absent, and `targetUserId` points to a non-existent user profile or a profile with a blank `anonymousId`):
- Do **NOT** fall back to querying Firestore with `targetUserId`.
- Do **NOT** aggregate artifacts across account boundaries.
- Do **NOT** expose Firebase Auth UID in network queries or UI logs.
- Return an appropriate empty/unavailable Published state (`publishedArtifacts = emptyList()`, `hasMorePublished = false`).

## Exact Files to Modify
1. `app/src/main/java/com/saurabh/artifact/domain/profile/GetProfileDataUseCase.kt`
   - Refactor flow to resolve `targetProfile` first when `targetPersonaId` is absent.
   - Separate `profileLookupId`, `resolvedPersonaId`, and `publicArtifactQueryId`.
   - Ensure public artifact query receives `resolvedPersonaId` ONLY for non-self profiles.
2. `app/src/main/java/com/saurabh/artifact/ui/profile/ProfileViewModel.kt`
   - In `loadMorePublished()`:
     Determine `queryPersonaId` using strict precedence:
     1. `_targetPersonaId.value?.ifBlank { null }`
     2. `uiState.value.userProfile?.anonymousId?.ifBlank { null }`
     3. If neither is available and `!isSelf`, **STOP / do not execute query**.
     - Keep Self profile pagination on its existing private/account-owned path using `currentUserId`.
3. `app/src/test/java/com/saurabh/artifact/domain/profile/GetProfileDataUseCaseTest.kt`
   - Add unit tests for resolution rules (Tests A through G).
4. `app/src/test/java/com/saurabh/artifact/ui/profile/ProfileViewModelTest.kt`
   - Add unit tests for `loadMorePublished()` persona resolution and safety stops.

## Exact Files NOT to Modify
- `repository/ArtifactRepository.kt` (existing `getUserArtifacts` and `getUserArtifactsPage` implementation correctly handles `isSelf` vs `whereEqualTo("author.anonymousId", ...)`).
- `repository/UserRepository.kt` (profile streaming contracts remain intact).
- `navigation/NavigationStructure.kt` (`Profile` route schema with `userId` and `personaId` parameters remains intact).
- Firestore Rules, Cloud Functions, and Database Schemas.

## Privacy / Responsible Anonymity Impact
- Guarantees complete separation of account identity (Firebase UID) from public creative identity (Persona ID).
- Prevents cross-persona linkability or accidental UID leaks in Firestore query parameters.

## Database / Firebase Impact
- Zero schema changes.
- Zero rules changes.
- Existing composite indexes on `artifacts (author.anonymousId, isPublic, status, createdAt DESC)` remain fully utilized.

## Preservation of Account & Persona Model
- **ONE email/Firebase account = ONE Artifact account.** No multiple accounts created.
- **Persona isolation maintained**: Artifacts are queried strictly per anonymous persona ID (`author.anonymousId`). Artifacts from different personas belonging to the same underlying account are never aggregated in public view.
- **Persona-bound history** remains unchanged.

## Automated Test Plan
Expand `GetProfileDataUseCaseTest.kt` and `ProfileViewModelTest.kt` to cover:
- **Test A**: `targetPersonaId` exists -> query uses `targetPersonaId`.
- **Test B**: `targetPersonaId` absent + `targetUserId` exists -> profile resolves `anonymousId` -> query uses resolved `anonymousId`.
- **Test C**: `targetPersonaId` absent + `targetUserId` exists + `profile.anonymousId` missing/blank -> public Artifact query is NOT executed with UID (returns empty artifact list).
- **Test D**: `targetPersonaId` exists + `targetUserId` exists -> `personaId` wins.
- **Test E**: `loadMorePublished()` pagination with `targetPersonaId` -> uses `personaId`.
- **Test F**: `loadMorePublished()` pagination with `targetUserId` only -> resolves `userProfile.anonymousId` first; never queries with UID.
- **Test G**: Self Profile -> existing private/account-owned path (`users/{uid}/private/published_artifacts/artifacts`) is preserved.

## Physical Verification Plan
Device tests required during implementation execution:
A. Home Feed -> Creator Profile navigation and artifact load.
B. Mini Player -> Creator Profile navigation.
C. Expanded / Immersive Player -> Creator Profile navigation.
D. Multiple public ACTIVE artifacts load correctly under persona ID.
E. Private/non-ACTIVE artifacts remain hidden for other creators.
F. Persona isolation verified between distinct personas.
G. Verify via Firestore logs/diagnostics that Firebase UID is NEVER used in `author.anonymousId` queries.
H. Pagination in Creator Profile remains persona-scoped.
I. Identity-reset / persona-boundary behavior.
J. Self Profile view remains unchanged and loads private registry items.

## Evidence Classification
Level 2 — Code Evidence. Runtime verification will be performed when the implementation is executed on the physical device.
