# Production Investigation – Title Field Reset on Publishing Studio

## Problem Statement

Users encounter a production-blocking issue in the Publishing Studio (Details step). While typing in the **Title** field, the text immediately (or within ~500ms) disappears. This reset prevents the **Emotion** selector from becoming/staying "enabled" (as perceived by the user) and disables the "Continue" button, effectively blocking the publishing flow.

## Evidence Collected

### 1. ViewModel State Management
In [PublishingStudioViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioViewModel.kt), the `sessionState` is a `combine` of multiple flows. The `title` displayed in the UI is derived as:
```kotlin
val displayTitle = titleBuffer ?: draft.title ?: ""
```
- `titleBuffer` comes from `_titleInput`, which is updated immediately on every keystroke in `updateTitle()`.
- `draft` comes from `recordingRepository.observeDraft(id)`.

### 2. Debounced DB Write & Buffer Clearing
`updateTitle(title: String)` updates the buffer immediately for responsiveness, then debounces the database write by 500ms:
```kotlin
fun updateTitle(title: String) {
    _titleInput.value = title // Update buffer
    titleDebounceJob = viewModelScope.launch {
        delay(500)
        val result = recordingRepository.updateDraftMetadata(draftId, title, ...)
        if (result.isSuccess) {
            recordingRepository.updateStudioState(...)
            _titleInput.value = null // Clear buffer - authority returns to DB
        }
    }
}
```

### 3. Faulty Observation Predicate
In [RecordingRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/RecordingRepository.kt), the `observeDraft` flow uses an overly restrictive `distinctUntilChanged` check:
```kotlin
fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.get().observeDraftById(id)
    .distinctUntilChanged { old, new ->
        old?.id == new?.id &&
        old?.lifecycle == new?.lifecycle &&
        old?.reviewProgress == new?.reviewProgress
    }
```
> [!IMPORTANT]
> This predicate **ignores** changes to `title`, `emotion`, `titleCompleted`, `emotionCompleted`, and `approvalCompleted`.

## Execution Trace

1.  **User Input**: User types "My Artifact". `_titleInput` is updated to "My Artifact".
2.  **UI Update**: `sessionState` re-calculates. `displayTitle` uses `titleBuffer` ("My Artifact"). The TextField shows the correct text.
3.  **Debounce Timeout**: User stops typing for 500ms. The debounce job resumes.
4.  **Database Write**: `updateDraftMetadata` and `updateStudioState` are called. The database now correctly holds `title = "My Artifact"` and `titleCompleted = true`.
5.  **Room Emission**: Room detects the database change and emits the updated `ArtifactDraftEntity` to the `observeDraftById` flow.
6.  **Suppression**: `RecordingRepository.observeDraft` receives the new entity. It compares it to the previous one:
    - `id` is same.
    - `lifecycle` is same.
    - `reviewProgress` is same.
    - Result: `distinctUntilChanged` returns `true` (no change detected) and **drops the emission**.
7.  **Buffer Reset**: Back in the ViewModel, the success branch of the debounce job executes `_titleInput.value = null`.
8.  **UI Regression**: `sessionState` re-calculates because `_titleInput` changed.
    - `titleBuffer` is now `null`.
    - `draft` is the **OLD** stale object (the last one allowed through the predicate), where `title` is `""` and `titleCompleted` is `false`.
    - `displayTitle` becomes `""`.
9.  **Result**: The TextField resets to empty. The "Continue" button remains disabled because `state.titleCompleted` (derived from the stale `draft`) is `false`.

## Root Cause

The **`distinctUntilChanged`** predicate in `RecordingRepository.observeDraft` is missing critical fields required for the Publishing Studio. It suppresses database updates for metadata and completion flags, causing the ViewModel to fall back to stale data once its local input buffer is cleared.

## Confidence Level
**High (100%)**
The code paths in `PublishingStudioViewModel` and `RecordingRepository` create a textbook race condition/state-desync due to improper filtering of a shared state flow.

## Smallest Architectural Fix

Update the `distinctUntilChanged` predicate in `RecordingRepository.observeDraft` to include all fields used by the Studio UI, or remove the custom predicate entirely to rely on the default `equals()` check (which is safe for a single-row observation).

```kotlin
// Recommended Fix in RecordingRepository.kt
.distinctUntilChanged { old, new ->
    old?.id == new?.id &&
    old?.lifecycle == new?.lifecycle &&
    old?.reviewProgress == new?.reviewProgress &&
    old?.title == new?.title &&
    old?.emotion == new?.emotion &&
    old?.titleCompleted == new?.titleCompleted &&
    old?.emotionCompleted == new?.emotionCompleted &&
    old?.approvalCompleted == new?.approvalCompleted &&
    old?.reviewCompleted == new?.reviewCompleted
}
```

## Risk Assessment

- **Risk**: Very Low.
- **Side Effects**: Slightly more frequent emissions during transitions, but since these are exactly the emissions the UI needs to react to, it is the intended behavior. Performance impact is negligible as this flow is typically only active for the single draft currently being edited.
- **Verification**: Can be verified with a unit test that mocks `DraftDao.observeDraftById`, emits a metadata-only change, and asserts that `RecordingRepository.observeDraft` forwards the emission.
