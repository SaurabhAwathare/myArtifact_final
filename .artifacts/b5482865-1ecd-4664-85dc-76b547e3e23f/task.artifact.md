# Task: Sigil System Refactor

Refactor the identity system to replace human-like avatars with abstract sigils.

- `[x]` **Phase 1: Model & Domain Refactor**
    - `[x]` Rename `AvatarConfig` to `SigilConfig` and update fields.
    - `[x]` Create `SigilEnums.kt` for refinements.
    - `[x]` Update `User` and `AuthorSnapshot` models to use `SigilConfig`.
- `[x]` **Phase 2: Renderer Implementation**
    - `[x]` Rename `AvatarRenderer` to `SigilRenderer`.
    - `[x]` Implement `GeometricSigilRenderer`.
    - `[x]` Delete `CartoonRenderer`.
- `[x]` **Phase 3: UI & Navigation**
    - `[x]` Rename and refactor `AvatarViewModel` to `SigilViewModel`.
    - `[x]` Rename and refactor `AvatarEditorScreen` to `SigilRitualScreen`.
    - `[x]` Update Navigation routes and graph.
- `[x]` **Phase 4: Migration & Cleanup**
    - `[x]` Verify serialization compatibility.
    - `[x]` Clean up unused "Avatar" resources and strings.
- `[x]` **Phase 5: Verification**
    - `[x]` Run unit tests (Migration & Determinism).
    - `[x]` Manual UI verification.
