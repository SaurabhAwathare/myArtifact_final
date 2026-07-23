# Implementation Plan: Sigil System Refactor (v2)

Refactor the identity system to replace human-like "Cartoon Avatars" with abstract, non-human "Sigils" discovered through deterministic generation.

## Goal
Align the project's visual identity with its mission: **"Thoughts over appearance"** and **"Voice over Vanity"**. Shift the user experience from **constructing** a persona to **revealing** a unique mark.

## User Review Required

> [!IMPORTANT]
> **Deterministic Identity**: Sigils are primarily generated from the user's `Identity Seed`. Users do not "design" the geometry; they "reveal" it and apply minor refinements (e.g., color palette).
> **Terminology Shift**: "Avatar" becomes "Sigil". "Editor" becomes "Ritual" or "Reflection".

## Proposed Changes

### [Component] Model & Domain

#### [MODIFY] [SigilConfig.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/AvatarConfig.kt)
- Rename from `AvatarConfig`.
- **Primary Source**: `seed: String`.
- **Refinement Fields**:
    - `palette: SigilPalette` (Abstract color sets).
    - `variant: SigilVariant` (Light/Dark/Ghost).
    - `weight: Float` (Line thickness/boldness).
    - `style: SigilStyle` (Filled vs. Outline).
- **Invariants**: Geometry is *never* directly editable. It is derived via `Hash(seed)`.

#### [NEW] [SigilEnums.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/avatar/SigilEnums.kt)
- Define `SigilPalette`, `SigilVariant`, and `SigilStyle`.

### [Component] UI Renderer

#### [MODIFY] [SigilRenderer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/avatar/renderer/AvatarRenderer.kt)
- Rename interface.
- Update to accept `SigilConfig`.

#### [NEW] [GeometricSigilRenderer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/avatar/renderer/GeometricSigilRenderer.kt)
- Uses the `seed` to seed a local `Random` instance.
- Draws overlapping geometric primitives (circles, polygons, arcs) in a 100x100 normalized space.
- Applies `SigilConfig` refinements (weight, style, palette) to the generated paths.

#### [DELETE] [CartoonRenderer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/avatar/renderer/CartoonRenderer.kt)

### [Component] UI Screens

#### [MODIFY] [SigilViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/avatar/AvatarViewModel.kt)
- Rename from `AvatarViewModel`.
- Logic focused on "Discovering" the sigil.

#### [MODIFY] [SigilRitualScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/avatar/AvatarEditorScreen.kt)
- Rename from `AvatarEditorScreen`.
- UI focused on "Refining the Reflection" rather than "Editing the Face".

---

## Verification Plan

### Automated Tests
- **Determinism Test**: Verify that the same `seed` + `refinement` produces the same `DrawScope` commands.
- **Migration Test**: Verify `AvatarConfig` (v1) parses into `SigilConfig` (v2) gracefully.

### Manual Verification
- Verify the "Sigil Ritual" flow: User enters seed -> Sigil revealed -> User selects refinement.
- Check "Human Library" (Feed) for the new abstract visual language.

## Engineering Trade-offs
- **Discovery vs. Control**: Limits user agency in favor of mission alignment. Some users may want "more control," but the "Product Decision Framework" (Section 7) prioritizes "Authentic Expression" over "Visual Validation."

## Evidence Level
**Code Evidence**: Level 2. The current `CartoonRenderer` and `AvatarConfig` are legacy artifacts of traditional social design and conflict with the approved `Artifact Project Reference Document`.
