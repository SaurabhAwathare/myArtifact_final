# AI Logic Refinement: Safety & Generation Consolidation

This plan refines the AI architecture by consolidating safety orchestration into the Domain layer (Use Case) and streamlining the AI Service. This reduces redundant processing and prepares the codebase for actual LLM integration.

## Proposed Changes

### Domain Layer (Orchestration)

#### [GetReflectionPromptUseCase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/prompt/GetReflectionPromptUseCase.kt)

- **Consolidate Safety Decisions:** The Use Case will now be the single orchestrator.
- **Short-circuiting:** If `SafetyEvaluator` detects a crisis or high risk, it will return the safety-suggested prompt immediately without calling the AI service.
- **Controlled Generation:** The AI service is only invoked for safe or low-risk contexts.

---

### Service Layer (AI Implementation)

#### [ReflectionAIService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/ReflectionAIService.kt)

- **Remove Redundant Safety:** Delete the redundant call to `safetyEvaluator.evaluate()` inside `generatePrompt`.
- **Pure Generation:** Focus the implementation on logic that simulates or calls an LLM.
- **Interface Stability:** Keep the public interface unchanged so ViewModels/Repositories don't need updates.

## Verification Plan

### Automated Tests
- I will create a unit test `GetReflectionPromptUseCaseTest.kt` to verify:
    - High-risk input returns a safety prompt and `isCrisis = true`.
    - Low-risk input calls the AI service and returns a generated prompt.
    - AI service failure still results in a valid fallback prompt.

### Manual Verification
- **Code Audit:** Verify that `SafetyEvaluator` is now only called once per request flow.
- **Log Inspection:** Check that "SafetyEvaluator" warnings appear when high-risk text is passed from the UI (simulated via debug or manual input).
