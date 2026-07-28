# UX Decision Review: Feed Recommendation Labels

This document evaluates the design direction for informational text labels in the Home feed (e.g., "From a presence you resonate with"). Based on the initial investigation, we have determined that while these labels provide valuable algorithm transparency, their current persistent display may conflict with Artifact's "Calm UX" and "Voice-First" principles.

## Goal
Provide algorithm transparency and explainability without increasing cognitive load or interrupting the minimalist listening experience.

## Evaluation of Options

| Option | Description | Mission Alignment | Listening Experience | Cognitive Load | Visual Simplicity | Transparency |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A: Keep Always-Visible** | Current implementation. | ⚠️ Moderate | ❌ Interrupted | ❌ High | ❌ Low | ✅ Full |
| **B: Info Icon + Tooltip** | Replace text with icon; show text on tap. | ✅ High | ✅ Calm | ⚠️ Moderate | ✅ High | ⚠️ Partial |
| **C: "Why this?" Menu** | Move explanation to the "More" options sheet. | ✅ Excellent | ✅ Excellent | ✅ Low | ✅ Maximum | ✅ On-Demand |
| **D: First-Item Only** | Show label only for the first item in a session. | ⚠️ Low | ⚠️ Variable | ⚠️ Variable | ⚠️ Variable | ❌ Inconsistent |
| **E: Remove Completely** | Delete the label and metadata logic. | ❌ Poor | ✅ Excellent | ✅ Low | ✅ Maximum | ❌ None |

---

## Detailed Analysis: Option C (Preferred Direction)

### Recommendation: **Option C – "Why am I hearing this?" in Options Sheet**

**Reasoning:**
*   **Revealing Information on Demand**: Aligns with the Artifact philosophy of avoiding "noise" until the listener explicitly seeks more context.
*   **Zero Visual Clutter**: Removes the persistent row above the card, allowing the feed to breathe and focusing the user entirely on the Artifact and its creator.
*   **Consolidation**: The "More" menu (`ArtifactOptionsSheet`) already handles feedback and reporting; adding "Why am I hearing this?" centralizes all meta-interactions with the algorithm in one place.
*   **Deeper Context**: Moving to a menu allows us to provide more detailed explanations if desired in the future, without worrying about screen real estate.

### Implementation Implications
1.  **Modify `ArtifactFeedCard`**: Remove the recommendation row.
2.  **Update `ArtifactOptionsSheet`**: Add an `OptionItem` for "Why am I hearing this?".
3.  **UI Feedback**: When selected, show a simple dialog or Snackbar explaining the reason (e.g., "This was recommended because it resonates with your current mood of 'Hopeful'").

---

## Decision Status: **PENDING USER REVIEW**

> [!IMPORTANT]
> **Action Required**: Please review the options above. If you approve of **Option C**, we will proceed with an Implementation Plan.

> [!TIP]
> **Alternative View**: If you feel transparency is too important to hide behind a menu, **Option B** (Info Icon) provides a middle ground that keeps a "visual anchor" for the explanation without the full text string.