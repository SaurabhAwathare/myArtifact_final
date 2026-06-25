# Artifact AI Agent Guidelines

This document provides future AI coding agents with project-specific engineering rules and guidance for modifying the Artifact codebase.

---

## Before Modifying Code

You **MUST** read the following documents to understand the architectural constraints:
- [Publishing Flow Invariants](architecture/PublishingFlowInvariants.md)
- [Repository Ownership](architecture/RepositoryOwnership.md)
- [Lifecycle Documentation](architecture/LifecycleDocumentation.md)
- [Architecture Decision Records (ADRs)](adr/)

---

## Engineering Rules

1.  **Preserve Single Source of Truth (SSOT)**: The database is the authority. UI state is temporary.
2.  **Repository Ownership**: Business orchestration belongs in the Repository. DAOs perform persistence only.
3.  **No Duplicate Ownership**: Do not introduce multiple writers for the same persisted field.
4.  **Explicit Lifecycle Transitions**: Never use ordinal comparisons for lifecycle states. Use `canTransitionTo()`.
5.  **Atomic Finalization**: Recording completion must be atomic and occur inside a transaction.
6.  **Idempotency**: Ensure operations like `finalizeRecording()` can be called multiple times safely.
7.  **Documentation First**: Update architecture documents whenever the architecture changes.
8.  **Verify with Tests**: Add unit or integration tests for every architectural change.
9.  **Simplicity**: Prefer simple, readable code over clever optimizations unless performance data requires it.

---

## Compose Best Practices

**Lazy List Key Stability**: Every dynamic `LazyColumn`, `LazyRow`, `LazyVerticalGrid`, `LazyHorizontalGrid`, or Paging list must use stable unique keys (e.g., Database ID, Firestore ID, UUID, or stable model identifier) to ensure Compose state preservation, performance, and animation stability. Avoid using index-based keys for dynamic collections. Static lists with immutable ordering may use default keys when justified with a comment.

---

## Investigation Rules

Before implementing a change:
1.  **Understand Ownership**: Identify which component owns the data you are modifying.
2.  **Trace Path**: Trace the execution path from UI to Persistence.
3.  **Identify Invariants**: Determine which architectural invariants are affected by your change.
4.  **Review ADRs**: Check if a similar decision was already made and documented.

---

## Technical Stack
- **Language**: Kotlin
- **Architecture**: MVVM + Repository Pattern
- **Persistence**: Room Database
- **Background Work**: WorkManager
- **UI**: Jetpack Compose
- **DI**: Hilt
