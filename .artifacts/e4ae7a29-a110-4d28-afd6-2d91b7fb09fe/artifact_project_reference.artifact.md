# Artifact Project Reference Document

> [!IMPORTANT]
> **Title**: Artifact Project Reference Document
> **Version**: v1.0
> **Status**: Approved
> **Owner**: Artifact Project
> **Last Updated**: 2026-07-23
> **Purpose**: This document serves as the single source of truth for the Artifact project's mission, philosophy, engineering standards, and investigation methodologies. It ensures that the project's core mission always drives technical and product decisions.

---

## Part I: Foundational Principles (Immutable Core)

These sections define the "Soul" of the Artifact project. They are rarely changed and serve as the ultimate authority for resolving conflicts in product or engineering decisions.

### 1. Mission
**"Connect people through authentic human experiences, not identities."**
Artifact exists to provide a space where the depth of human experience is shared and preserved through voice, transcending the limitations of identity-based social media.

### 2. Vision
**"Building a Human Library of authentic human experiences."**
We envision a world where every individual's authentic voice contributes to a collective archive of human emotion, reflection, and truth, accessible to all in a **Sanctuary** of mutual respect.

### 3. Core Values
- **Thoughts over appearance**: Prioritizing the substance of what is said over how it looks.
- **Authenticity over popularity**: Valuing genuine expression over social validation or engagement metrics.
- **Listening over broadcasting**: Focusing on deep engagement and understanding rather than mass distribution.
- **Reflection over impulsiveness**: Encouraging mindful interaction and intentional pacing.
- **Trust over growth**: Prioritizing community health and safety over rapid expansion.
- **Privacy over exposure**: Protecting user identity as a fundamental right.
- **Human connection over social validation**: Building meaningful relationships through shared experience.

### 4. Product Philosophy
- **Voice-first Sanctuary**: Audio is the primary medium, creating an emotionally safe space for expression.
- **Voice over Vanity**: Eliminating visual triggers (like profile pictures or filters) to focus entirely on the human voice and the user's **Sigil**.
- **Reflection over Impulsiveness**: Using design patterns that encourage users to think before they speak or respond.
- **Emotional Safety**: Every feature must protect the user's emotional well-being and maintain the **Sanctuary**.

### 5. Responsible Anonymity
- **Authentication Required**: Users must be authenticated to interact, ensuring a baseline of accountability.
- **Community Anonymity**: Users are anonymous to others via **Sigils** and nicknames, protecting their real-world identity.
- **Platform Accountability**: Users remain accountable to the platform to prevent harassment and maintain community standards.
- **PII Protection**: Absolute protection of real names and emails.
- **Responsible Conduct**: Clear standards for respect, privacy, and legality in anonymous interactions.

### 6. Listening Principles
- **Listen Before You Publish**: Encouraging creators to listen to their own reflections before sharing them with the **Sanctuary**.
- **Listen Before You Respond (LBYR)**: Requiring users to listen to an **Artifact** (typically 95% threshold) before they can comment or **Resonate**.
- **Mindful Consumption**: Moving away from "scrolling" toward "listening".

### 7. Product Decision Framework
Every proposed feature must answer the following:
1.  Does it encourage authentic expression?
2.  Does it strengthen meaningful connection?
3.  Does it encourage listening?
4.  Does it reduce loneliness?
5.  Does it preserve privacy?
6.  Does it improve trust?
7.  Does it support **Responsible Anonymity**?
8.  Would it still be valuable without increasing engagement metrics?

### 8. Terminology: The Language of the Sanctuary
- **Artifact**: A voice recording with associated metadata; the unit of human experience.
- **Resonance**: The primary way users interact with an **Artifact**; an emotional reflection.
- **Sanctuary**: The app environment, designed for safety, respect, and deep listening.
- **Sigil**: A user's unique, non-human visual identifier that replaces the profile picture.
- **Draft**: An unpublished, local recording awaiting final reflection.
- **Human Library**: The collective archive of all shared **Artifacts**.
- **Listen Before You Respond (LBYR)**: The core policy governing interaction and engagement.

---

## Part II: Operational Guidance (Evolving Execution)

These sections define the "Body" of the Artifact project. They represent the current best practices and methodologies for realizing the mission. These are expected to evolve as the project scales.

### 9. UX Principles
- **Calm UI**: Minimalist, non-addictive aesthetics that reduce cognitive load.
- **No Popularity Systems**: No public follower counts, "likes", or leaderboards.
- **No Addictive Design**: No infinite scrolls, autoplay, or attention-grabbing notifications.
- **Emotional Safety**: Designing for vulnerability and trust through intentional friction (e.g., LBYR).

### 10. Engineering Context & Architecture
- **Reliability over Feature Velocity**: Prioritize system stability and data integrity over rapid feature delivery.
- **Solo-Founder Constraints**:
    - Designed for a single developer's maintenance capacity.
    - Preference for maintainable, high-leverage solutions (Firebase, Idiomatic Kotlin).
- **Architecture**: MVVM + Repository Pattern.
- **Persistence Layer**: Room Database is the authoritative **Single Source of Truth (SSOT)**.
- **Background Orchestration**: WorkManager for resilience against process death.
- **Cloud Infrastructure**: Firebase (Firestore, Auth, Storage, Cloud Functions).
- **Island Architecture**: Designing for resilience and isolated recovery of specific components.

### 11. Architecture Evaluation & Invariants
- **Atomic Transactions**: Persistence operations must be atomic and occur inside transactions.
- **Idempotency**: Operations (Sync, Cleanup, Recovery) must be safe to call multiple times.
- **Explicit Lifecycle Transitions**: No ordinal comparisons; use `canTransitionTo()`.
- **Durable Deletion Boundary**: **Drafts** must reach terminal states before final purging.

### 12. Feature Evaluation Criteria
- **Mission Alignment**: Does it serve the core goal of authentic connection?
- **Community Health**: Impact on the **Sanctuary's** safety and respect.
- **Sustainability**:
    - **Firebase Cost Sensitivity**: Prioritize cost-efficient architectural decisions.
    - **Moderation Burden**: Impact on safety operations for a limited resource environment.
    - **Engineering Effort**: Complexity vs. value for a solo developer.

### 13. Bug Investigation Methodology
- **Narrow Questions**: Breaking issues into specific, answerable production questions.
- **Sub-phase Progression**: Building a solid evidence chain before proposing fixes.
- **Minimalist Investigation**: Investigating only what is needed to reach the required evidence level.

### 14. Investigation Hierarchy & Evidence Standards
1.  **Level 1: Observation**: Bug reports, logs, or symptoms (Symptoms).
2.  **Level 2: Code Evidence**: Static analysis identifying logic violations (Suspects).
3.  **Level 3: Runtime Evidence**: Corroboration via state emissions or debugging logs (Proof).
4.  **Level 4: Reproduced & Verified**: Physical/Emulator reproduction and regression testing (Conviction).

### 15. Investigation Report Format
- **Problem Statement**: Clear definition of the issue.
- **Evidence (Confidence Level)**: Detailed evidence supporting the findings.
- **Root Cause Analysis**: Explanation of why the failure occurred.
- **Fix Details**: The surgical code changes implemented.
- **Verification Plan**: Steps taken to confirm the fix.
- **Regression Risk**: Assessment of potential side effects.

### 16. AI Credit & Investigation Budget
- **Context Preservation**: Keeping the context window clean by delegating noisy tasks.
- **Budget-Aware Investigation**: Performing minimal necessary investigation to preserve AI context for high-level architecture.
- **Evidence-First Action**: Taking action only after reaching at least Level 2 (Code Evidence).

### 17. Workflow & Verification Standards
- **Pre-merge Checklist**: Invariants, SSOT, Repository ownership, and Documentation updates.
- **Documentation Standards**: Every doc must include Purpose, Scope, Responsibilities, SSOT, and Failure Recovery.
- **Manual Verification**:
    - **Recording Sequences**: Rapid action testing for mutex guards.
    - **Publishing Integrity**: Testing background workers and upload resume logic.
    - **Playback Authority**: Verifying Media3 audio focus and session sync.
    - **Engagement Cycles**: Verifying **LBYR** thresholding and Firestore security rules.

---

## Part III: Cross-References

This document should be read in conjunction with the following specialized documentation:

- **[Publishing Flow Invariants](file:///F:/Android Project/01/.artifacts/e4ae7a29-a110-4d28-afd6-2d91b7fb09fe/publishing_flow_invariants.artifact.md)**: The immutable rules governing the core artifact pipeline.
- **[Architecture Decision Records (ADRs)](file:///F:/Android Project/01/docs/adr/)**: Specific technical decisions and their justifications.
- **[Architecture Checklist](file:///F:/Android Project/01/docs/architecture_checklist.md)**: A tactical list for evaluating code changes against the project's structure.
- **[UI Style Guide](file:///F:/Android Project/01/docs/ui_style_guide.md)**: Detailed guidance on maintaining the **Calm UI** and **Sanctuary** aesthetics.
