# Task List - Phase 7: Defect 2 (UI Navigation Decoupling)

- `[x]` Update `PublishingStudioViewModel`
    - `[x]` Add `_currentStepOverride` StateFlow
    - `[x]` Update `sessionState` combine logic to incorporate override
    - `[x]` Implement UI-only `previousStep()`
    - `[x]` Implement conditional `nextStep()` (persist only when advancing)
    - `[x]` Reset override in `loadDraft()`
- `[x]` Static Verification of Architecture Invariants
- `[x]` Final Build Check
