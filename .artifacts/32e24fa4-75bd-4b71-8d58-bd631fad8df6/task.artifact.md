# Task List - Final Repository Decomposition

- `[x]` **Phase 1: Engagement Extraction**
    - `[x]` Create `ArtifactEngagementRepository` with `recordPlay` and `submitPrivateFeedback` logic
    - `[x]` Add `ArtifactEngagementRepository` to Hilt DI (via `@Inject constructor`)
- `[x]` **Phase 2: Prompt Behavior Extraction**
    - `[x]` Create `ReflectionPromptManager` in `domain.prompt` package
- `[x]` **Phase 3: Core Repository Refactoring**
    - `[x]` Update `ArtifactRepository` to delegate to `ArtifactEngagementRepository` (Bridge)
    - `[x]` Update `ArtifactRepository` to delegate to `ReflectionPromptManager` (Bridge)
    - `[x]` Add legacy compatibility markers to `uploadTranscript` and `fetchTranscript`
    - `[x]` Remove unused dependencies from `ArtifactRepository` (e.g., `ReflectionAIService`, `PersonalizationEngine`)
- `[x]` **Phase 4: Verification**
    - `[x]` Run unit tests for `ArtifactRepository`, `ArtifactEngagementRepository`, and `ReflectionPromptManager`
    - `[x]` Verify build success
- `[x]` **Phase 5: Caller Migration (Post-Verification)**
    - `[x]` Migrate `FeedViewModel` to use `ArtifactEngagementRepository` directly
    - `[x]` Migrate `GetReflectionPromptUseCase` to use `ReflectionPromptManager` directly
    - `[x]` Clean up bridge methods in `ArtifactRepository` if no longer used
