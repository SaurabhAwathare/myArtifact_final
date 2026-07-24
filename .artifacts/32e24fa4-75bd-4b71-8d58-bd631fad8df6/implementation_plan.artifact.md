# Implementation Plan - Final Repository Decomposition & Engagement Extraction

This plan pivots the repository strategy based on the final architecture assessment feedback. We will focus on extracting a genuine business domain (Engagement) and transitioning AI behavior out of the repository layer, while preserving legacy code without unnecessary encapsulation.

## User Review Required

> [!IMPORTANT]
> **Shift from Repo-Creation to Simplification**: We are abandoning the `TranscriptRepository` and `ReflectionPromptRepository` ideas in favor of a more domain-aligned `ArtifactEngagementRepository` and a behavioral `ReflectionPromptManager`.

## Proposed Changes

### 1. Artifact Engagement Domain
Extract user-centric interaction and personalization logic into a dedicated repository.

#### [NEW] [ArtifactEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactEngagementRepository.kt)
- **Responsibilities**:
    - `recordPlay(userId, emotion)`: Logs playback and updates personalization signals.
    - `submitPrivateFeedback(artifactId, userId, type)`: Handles "Not for me" and safety concerns.
- **Dependencies**: `FirebaseFirestore`, `PersonalizationEngine`, `SettingsRepository`, `DiagnosticLogger`.

### 2. Prompt Behavior Domain
Move AI-generation behavior out of the data repository.

#### [NEW] [ReflectionPromptManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/prompt/ReflectionPromptManager.kt)
- **Responsibilities**:
    - `getSmartReflectionPrompt(emotion, context, timeOfDay)`: Logic for AI prompt generation and fallback.
- **Dependencies**: `ReflectionAIService`.

### 3. ArtifactRepository Refactoring
Clean up the core repository to focus on metadata, discovery, and orchestration.

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- **Bridges**: Update `recordPlay`, `submitPrivateFeedback`, and `getSmartReflectionPrompt` to delegate to the new components.
- **Documentation**: Mark `uploadTranscript` and `fetchTranscript` as **LEGACY COMPATIBILITY** code for eventual deletion.
- **Dependency Reduction**: Transition towards removing `PersonalizationEngine`, `SettingsRepository`, and `ReflectionAIService`.

### 4. Caller Migration (Initial Wave)
Update key callers to use specialized repositories directly where appropriate.

#### [MODIFY] [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt)
- Update to use `ArtifactEngagementRepository` for `recordPlay` and `submitPrivateFeedback`.

#### [MODIFY] [GetReflectionPromptUseCase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/prompt/GetReflectionPromptUseCase.kt)
- Update to use `ReflectionPromptManager` instead of `ArtifactRepository`.

---

## Verification Plan

### Automated Tests
- Create `ArtifactEngagementRepositoryTest.kt`.
- Create `ReflectionPromptManagerTest.kt`.
- Update `ArtifactRepositoryTest.kt` to verify bridge delegation or removal.
- Run `gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.repository.*"`

### Manual Verification
- Deploy app and verify that "recordPlay" (personalization) and "Private Feedback" still function correctly in the Feed.
- Verify that reflection prompts are still generated when starting a new session.
