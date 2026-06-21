# Home Feed Density Optimization

Reduce excessive vertical whitespace between Home Feed artifact cards while preserving the cinematic "floating artifact" aesthetic and maintaining waveform readability.

## User Review Required

> [!IMPORTANT]
> This plan involves removing external margins from the `ArtifactCard` component. This will affect all usages of `ArtifactCard` in the Home Feed. Usage in the Profile screen is unaffected as it uses `ProfileArtifactCard`.

- **Technical Trade-off**: Removing the margin from the component itself shifts the responsibility of spacing to the parent container (`LazyColumn`). This is more idiomatic in Compose but requires ensuring all parent containers provide appropriate spacing.

## Proposed Changes

### UI Components

#### [ArtifactCard.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/components/ArtifactCard.kt)

- Remove `.padding(vertical = Spacing.Medium)` from the `cardModifier` in `ArtifactCard`.
- Remove `.padding(vertical = Spacing.Medium)` from the `modifier` in `LightweightArtifactCard`.
- Apply `padding(vertical = Spacing.Medium)` to `ArtifactCardPreview` and `PreviewArtifactCardAtmospheric` to maintain visual isolation in previews.

```diff
-    val cardModifier = modifier
-        .fillMaxWidth()
-        .padding(vertical = Spacing.Medium)
-        .clip(MaterialTheme.shapes.large)
+    val cardModifier = modifier
+        .fillMaxWidth()
+        .clip(MaterialTheme.shapes.large)
```

```diff
-    Box(
-        modifier = modifier
-            .fillMaxWidth()
-            .padding(vertical = Spacing.Medium)
-            .clip(MaterialTheme.shapes.large)
+    Box(
+        modifier = modifier
+            .fillMaxWidth()
+            .clip(MaterialTheme.shapes.large)
```

---

## Verification Plan

### Automated Tests
- I will run the existing Compose Previews for `ArtifactCard` to ensure they still render correctly with the new preview-specific padding.
- `gradle_build("app:assembleDebug")` to ensure no compilation errors.

### Manual Verification
- **Visual Audit**: I will use `render_compose_preview` on `ArtifactCardPreview` and `PreviewArtifactCardAtmospheric`.
- **Feed Density Check**:
    - Current State: ~1.5 cards visible.
    - Target State: ~2.2 cards visible.
- **Regression Check**:
    - Verify no shadow clipping between cards.
    - Verify recommendation labels in `ArtifactFeedCard` are now closer to the card body (8dp gap instead of 24dp).
    - Verify first and last items in the feed have correct spacing from top/bottom.
