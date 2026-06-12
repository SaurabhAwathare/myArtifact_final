# Enhancing Offline Mode for Smart Reflections

The current AI service relies on a cloud-based LLM with an 8-second timeout, which causes significant latency when the device is offline. Additionally, the offline fallback chooses prompts randomly, leading to potential repetition and a "static" feel compared to AI-generated content.

This plan introduces a **Fail-Fast Hybrid AI** approach that detects connectivity issues immediately and uses a "smart" local selection engine to provide diverse, context-aware reflections even when offline.

## Proposed Changes

### Utilities

#### [NetworkUtils.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/util/NetworkUtils.kt)

- Add `isNetworkAvailable(context: Context)` to allow the AI service to skip cloud requests immediately when offline.

---

### Data Layer

#### [PromptDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/PromptDao.kt)

- Add queries to fetch the "Least Recently Used" prompts, both globally and filtered by mood. This ensures that offline reflections feel fresh and diverse.

```kotlin
@Query("SELECT * FROM prompts WHERE mood = :mood ORDER BY lastUsedTimestamp ASC LIMIT 1")
suspend fun getOldestPromptByMood(mood: String): PromptEntity?

@Query("SELECT * FROM prompts ORDER BY lastUsedTimestamp ASC LIMIT 1")
suspend fun getOldestPrompt(): PromptEntity?
```

#### [PromptRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/PromptRepository.kt)

- Implement `getSmartFallback(mood: String?)`:
    - Selects the least recently used prompt.
    - Applies **Template Expansion**: Replaces `[EMOTION]` in the prompt text with the current emotion (e.g., "How does [EMOTION] feel in your body?" becomes "How does Sadness feel in your body?").
- Ensure `recordUsage` is called so the "Least Recently Used" logic works.

---

### Service Layer

#### [ReflectionAIService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/ReflectionAIService.kt)

- Update `ReflectionAIServiceImpl` to:
    - Check connectivity before initiating AI generation.
    - If offline, immediately return a result from the new `getSmartFallback` engine.
    - Log the specific reason for fallback (Offline vs. Timeout vs. Error) for better debugging.

---

### Assets

#### [prompts.json](file:///F:/Android Project/01/app/src/main/assets/prompts.json)

- Update several prompts to include the `[EMOTION]` placeholder to leverage the new template engine.

## Verification Plan

### Automated Tests
- **NetworkUtilsTest**: Verify connectivity detection works (mocking ConnectivityManager).
- **PromptRepositoryTest**:
    - Verify `getSmartFallback` returns the oldest used prompt.
    - Verify template replacement correctly injects the emotion.
- **ReflectionAIServiceTest**: Verify it skips the network call when `isNetworkAvailable` is false.

### Manual Verification
1.  **Offline Test**: Turn off Wi-Fi/Data and trigger a reflection. Verify it appears **instantly** without waiting for the 8s timeout.
2.  **Repetition Test**: Trigger reflections multiple times while offline. Verify that the prompts rotate through the database rather than repeating the same one.
3.  **Template Test**: Verify that prompts with `[EMOTION]` correctly show the detected emotion in the UI.
