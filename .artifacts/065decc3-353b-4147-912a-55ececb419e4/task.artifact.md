# Task: Phase 2 - Local Draft Observation in Player

- [x] Update `DraftRepository` to support local artifact observation
    - [x] Inject `DraftToArtifactMapper`
    - [x] Inject `UserRepository`
    - [x] Implement `observeDraftAsArtifact(id: String)`
- [x] Refactor `GetPlayerContextUseCase` for branching observation
    - [x] Inject `DraftRepository`
    - [x] Branch `observeMetadata` based on `isDraft`
    - [x] Implement `observeDraftMetadata` (local-only)
    - [x] Implement `observePublishedMetadata` (Firestore-backed)
- [x] Static Verification
    - [x] Confirm no duplicate observers via `flatMapLatest`
    - [x] Confirm layer separation
    - [x] Verify project compilation
