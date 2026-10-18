# Implementation Plan: Artifact Question Bank — Phase 1 Blocker Resolution

Corrected technical specification for Phase 1 of the Artifact Question Bank improvements. Sourced strictly from static code analysis of `PromptRepository`, `PromptDao`, `ReflectionPromptManager`, `RecordingViewModel`, `ArtifactDao`, and `UserRepository`.

---

## 1. Executive Summary & Context

Phase 1 establishes local question consumption, depth-based Creator progression, non-repetition via prompt exclusion, and recycling without AI overhead, schema migrations, or new external services.

This corrected plan resolves two specific technical blockers identified during architecture review:
1. **Eligible-pool exhaustion:** Prevents prompt starvation on the recording screen when all prompts for a Creator's current `maxDepth` have been consumed while higher-depth prompts remain unconsumed.
2. **Completed Artifact count correctness:** Disambiguates `nextEpisodeNumberPreview`, `ArtifactDao.getMaxPublishedEpisodeNumber()`, and `userProfile.artifactsCount` to establish an authoritative, local-first signal for Creator progression (`completedCount`) that survives artifact deletions, offline usage, question skipping, and question-bank recycling.

---

## 2. Technical Findings Classified by Confidence

| Finding | Confidence | Static Code Evidence & Reference |
| :--- | :--- | :--- |
| **F1: `getConsumedCount()` is NOT Creator progression** | **HIGH (100%)** | [`PromptDao.kt:L41`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/PromptDao.kt#L41): `@Query("SELECT COUNT(*) FROM prompts WHERE isConsumed = 1")`. Sourcing progression from `getConsumedCount()` causes question skipping to advance progression (violating Principle 1) and question-bank recycling to reset progression back to Depth 1 (violating Principle 3). |
| **F2: Global recycling fails on depth-pool exhaustion** | **HIGH (100%)** | [`PromptRepository.kt:L52`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/PromptRepository.kt#L52): `promptDao.get().getUnconsumedCount() == 0`. When a Depth 1 Creator consumes all Depth 1 prompts, `getUnconsumedCount()` is > 0 due to unconsumed Depth 2–4 prompts. Global recycling never triggers, and `getOldestPrompt()` selects higher-depth prompts prematurely. |
| **F3: `getMaxPublishedEpisodeNumber()` is a MAX, not a count** | **HIGH (100%)** | [`ArtifactDao.kt:L122`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactDao.kt#L122): `SELECT MAX(episodeNumber) FROM artifacts ...`. `maxEpisode` is an ordinal high-water mark. If Episode 2 of 3 is deleted, `maxEpisode` remains 3 while active count drops to 2. |
| **F4: `artifactsCount` is server-authoritative active count** | **HIGH (100%)** | [`User.kt:L40`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/model/User.kt#L40), [`UserRepository.kt:L1542`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/UserRepository.kt#L1542), [`PublishingManager.kt:L183`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingManager.kt#L183). `artifactsCount` increments on publish and decrements on soft-delete. |
| **F5: `nextEpisodeNumberPreview` is a UI preview, not a count** | **HIGH (100%)** | [`RecordingViewModel.kt:L74`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/RecordingViewModel.kt#L74), [`PublishingStudioViewModel.kt:L196`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioViewModel.kt#L196). `nextEpisodeNumberPreview` equals `maxOf(maxLocalEpisode, artifactsCount) + 1L`. |
| **F6: Default parameter `= 0` causes silent progression demotion** | **HIGH (100%)** | [`ReflectionPromptManager.kt:L24`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/prompt/ReflectionPromptManager.kt#L24), [`PromptRepository.kt:L38`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/PromptRepository.kt#L38). If `completedCount: Int = 0` has a default argument, callers omitting it default to 0, locking experienced Creators to Depth 1 prompts. |

---

## 3. Resolution of Blocker 1: Eligible-Pool Exhaustion & Depth Recycling

### Problem Mechanics
When a Creator's current progression unlocks up to `maxDepth` (e.g. `maxDepth = 1` for `completedCount < 30`), and all prompts matching `depthLevel <= maxDepth` have been consumed (`isConsumed = 1`):
1. `promptDao.getNextEligiblePrompt(maxDepth, excludedPromptId)` returns `null`.
2. Existing fallback `getOldestPrompt(excludedPromptId)` returns an unconsumed prompt with `depthLevel > maxDepth` (e.g. Depth 3 or 4), exposing beginner Creators to overly complex prompts prematurely.
3. If `getOldestPrompt` is restricted to `depthLevel <= maxDepth` without depth-pool recycling, `getNewPrompt()` returns `null` because `getUnconsumedCount()` > 0 (due to unconsumed higher-depth prompts), preventing global recycling. The recording screen runs out of prompts.

### Corrective Algorithm in `PromptRepository.getNewPrompt`
1. **Query Primary Eligible Pool:**
   `var entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId)`
2. **Exclusion Fallback (Single-Prompt Pool):**
   If `entity == null` and `excludedPromptId != null`:
   `entity = promptDao.get().getNextEligiblePrompt(maxDepth, null)`
3. **Depth-Scoped Pool Recycling:**
   If `entity == null` and `promptDao.get().getUnconsumedCountForMaxDepth(maxDepth) == 0`:
   - Execute `promptDao.get().resetConsumedPromptsForDepth(maxDepth)`
   - Re-query `entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId)`
   - If `entity == null` and `excludedPromptId != null`:
     `entity = promptDao.get().getNextEligiblePrompt(maxDepth, null)`

### Added Room Queries in `PromptDao.kt`
```kotlin
@Query("SELECT COUNT(*) FROM prompts WHERE isConsumed = 0 AND depthLevel <= :maxDepth")
suspend fun getUnconsumedCountForMaxDepth(maxDepth: Int): Int

@Query("UPDATE prompts SET isConsumed = 0 WHERE depthLevel <= :maxDepth")
suspend fun resetConsumedPromptsForDepth(maxDepth: Int)
```

### Invariants Preserved
- Beginners (`maxDepth = 1`) are **never** served Depth 2, 3, or 4 prompts.
- The recording screen **never** runs out of eligible prompts.
- Skipping a prompt (`excludedPromptId`) prevents immediate re-selection during recycling unless the eligible pool size is 1.
- Experienced Creators (`maxDepth = 3`) continue to receive Depth 1, 2, and 3 prompts randomly, keeping simple questions available.

---

## 4. Resolution of Blocker 2: Authoritative Progression Signal

### Data Source Classification
1. `ArtifactDao.getMaxPublishedEpisodeNumber(userId)`: **High-Water Mark Local Episode Index** (`Long?`). Not a count.
2. `userProfile.artifactsCount`: **Active Server-Authoritative Artifact Count** (`Long`). Decrements on soft-delete.
3. `nextEpisodeNumberPreview`: **UI Display Preview** (`Long?`). Represents `maxOf(maxLocalEpisode, artifactsCount) + 1L`. Not a count.

### Authoritative Progression Signal Definition
Creator progression MUST be driven by historical published artifact count, defined as:
```kotlin
val completedCount = maxOf(maxLocalEpisode ?: 0L, userProfile?.artifactsCount ?: 0L).toInt()
```
- **Local-First & Offline Resilience:** Sourcing from `maxOf(maxLocalEpisode, artifactsCount)` guarantees that progression is available offline and monotonically non-decreasing.
- **Deletions & Skipping Immunity:** Deleting an old artifact or skipping prompts during recording never demotes a Creator's depth level.

### Depth Calculation Formula (`calculateMaxDepth`)
```kotlin
private fun calculateMaxDepth(completedCount: Int): Int {
    return when {
        completedCount < 30 -> 1
        completedCount < 100 -> 2
        completedCount < 250 -> 3
        else -> 4
    }
}
```

### Call-Site Verification & Compile-Time Safeguards
To prevent callers from silently defaulting `completedCount` to `0` (which would lock experienced Creators to Depth 1 prompts):
- `PromptRepository.getNewPrompt` MUST NOT have a default value for `completedCount`:
  `suspend fun getNewPrompt(completedCount: Int, excludedPromptId: String? = null): ReflectionPrompt?`
- `ReflectionPromptManager.getNextPrompt` MUST NOT have a default value for `completedCount`:
  `suspend fun getNextPrompt(completedCount: Int, excludedPromptId: String? = null): ReflectionPrompt?`
- The Kotlin compiler enforces that every call site explicitly supplies `completedCount`.

---

## 5. Minimum Required File Changes

### 1. `PromptDao.kt`
Path: [`app/src/main/java/com/saurabh/artifact/data/local/PromptDao.kt`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/PromptDao.kt)
- Add `getUnconsumedCountForMaxDepth(maxDepth: Int): Int` query.
- Add `resetConsumedPromptsForDepth(maxDepth: Int)` query.

### 2. `PromptRepository.kt`
Path: [`app/src/main/java/com/saurabh/artifact/repository/PromptRepository.kt`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/PromptRepository.kt)
- Update `getNewPrompt` signature to require `completedCount: Int` (no default value).
- Replace `consumedCount` query lookup with the `completedCount` parameter in `calculateMaxDepth`.
- Implement depth-scoped recycling and exclusion fallback in `getNewPrompt`.

### 3. `ReflectionPromptManager.kt`
Path: [`app/src/main/java/com/saurabh/artifact/domain/prompt/ReflectionPromptManager.kt`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/prompt/ReflectionPromptManager.kt)
- Update `getNextPrompt` signature to require `completedCount: Int` (no default value).
- Delegate `getNewPrompt(completedCount, excludedPromptId)`.
- Update legacy `getSmartReflectionPrompt` to accept `completedCount: Int = 0` for non-recording contexts.

### 4. `GetReflectionPromptUseCase.kt`
Path: [`app/src/main/java/com/saurabh/artifact/domain/prompt/GetReflectionPromptUseCase.kt`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/prompt/GetReflectionPromptUseCase.kt)
- Update `invoke` to accept optional `completedCount: Int = 0` and pass to `reflectionPromptManager.getSmartReflectionPrompt`.

### 5. `RecordingViewModel.kt`
Path: [`app/src/main/java/com/saurabh/artifact/ui/recording/RecordingViewModel.kt`](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/recording/RecordingViewModel.kt)
- Calculate `completedCount` from `maxOf(maxLocalEpisode ?: 0L, userProfile?.artifactsCount ?: 0L).toInt()`.
- Pass `completedCount` on initial prompt load (`loadInitialPrompt`) and next prompt skip (`nextPrompt`).

### 6. Test Suite
Paths:
- [`app/src/test/java/com/saurabh/artifact/repository/PromptRepositoryTest.kt`](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/repository/PromptRepositoryTest.kt)
- [`app/src/test/java/com/saurabh/artifact/repository/QuestionBankLifecycleTest.kt`](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/repository/QuestionBankLifecycleTest.kt)
- [`app/src/test/java/com/saurabh/artifact/domain/prompt/ReflectionPromptManagerTest.kt`](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/domain/prompt/ReflectionPromptManagerTest.kt)
- [`app/src/test/java/com/saurabh/artifact/ui/recording/RecordingQuestionExperienceTest.kt`](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/ui/recording/RecordingQuestionExperienceTest.kt)
- Update test calls to supply explicit `completedCount` arguments.
- Add tests for depth-scoped pool recycling and exclusion fallback edge cases.

---

## 6. Edge Case Validation & Test Plan

1. **Eligible-Pool Exhaustion (Depth 1):**
   - Seed 10 Depth 1 prompts and 20 Depth 2 prompts. Mark all 10 Depth 1 prompts as `isConsumed = 1`. Call `getNewPrompt(completedCount = 5)`.
   - Verify `resetConsumedPromptsForDepth(1)` is called, a Depth 1 prompt is returned, and NO Depth 2 prompt is selected.
2. **Exclusion During Depth Recycling:**
   - Consume all Depth 1 prompts. Call `getNewPrompt(completedCount = 5, excludedPromptId = "P10")`.
   - Verify Depth 1 pool is recycled and returned prompt is NOT "P10".
3. **Skipping Does Not Advance Depth:**
   - Call `nextPrompt()` 50 times without publishing an artifact (`completedCount = 0`).
   - Verify `maxDepth` remains 1 throughout all skips.
4. **Recycling Does Not Reset Progression:**
   - Creator with `completedCount = 35` (`maxDepth = 2`) triggers depth recycling.
   - Verify subsequent prompt selection uses `maxDepth = 2` (eligible for Depths 1 and 2).
5. **Artifact Deletion Immunity:**
   - Creator publishes 30 artifacts (`maxLocalEpisode = 30`), then soft-deletes 5 artifacts (`artifactsCount = 25`).
   - Verify `completedCount = maxOf(30, 25) = 30`, retaining `maxDepth = 2`.
6. **Compile-Time Call-Site Verification:**
   - Verify that omitting `completedCount` in `getNewPrompt` or `getNextPrompt` fails Kotlin compilation.

---

## 7. Principles Compliance Matrix

| Principle | Verification Result | Implementation Detail |
| :--- | :--- | :--- |
| **1. Question skipping does not advance progression** | **COMPLIANT** | Skipping calls `markAsConsumed(id)`, updating `prompts.isConsumed`. `completedCount` is sourced from published artifact count and is unaffected by `prompts.isConsumed`. |
| **2. Simple questions remain available to experienced Creators** | **COMPLIANT** | `getNextEligiblePrompt` uses `WHERE depthLevel <= :maxDepth`. Depth 1 prompts remain in the pool for Depth 2, 3, and 4 Creators. |
| **3. Progression survives question-bank recycling** | **COMPLIANT** | Recycling executes `UPDATE prompts SET isConsumed = 0`. `completedCount` is stored separately in profile/artifacts and is never reset by prompt recycling. |
| **4. Existing local-first architecture preserved** | **COMPLIANT** | Sourced strictly from local Room DB (`prompts`, `artifacts`) and cached User profile. |
| **5. No unnecessary Firebase reads, AI calls, schema migrations** | **COMPLIANT** | Zero new tables, zero schema changes, zero network calls. |
| **6. Question generation/expansion separate phase** | **COMPLIANT** | Scope restricted strictly to local prompt selection and progression mechanics. |

---

## 8. Final Approval Readiness Statement

With the resolution of Blockers 1 and 2:
- Eligible-pool exhaustion is handled deterministically via depth-scoped recycling.
- Creator progression is anchored to an authoritative, local-first completed artifact count signal.
- Compile-time signatures prevent silent defaulting of completed count to zero.

**Phase 1 is READY FOR APPROVAL.**
