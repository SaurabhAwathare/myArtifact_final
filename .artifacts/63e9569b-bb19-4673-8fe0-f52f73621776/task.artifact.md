# Phase 1: Critical Firestore Aggregate Integrity Task List

## Preparation
- `[ ]` Update `Artifact.kt` data model if needed

## Android App Implementation
- `[x]` Update `ArtifactPublishingRepository.kt` to initialize counts
- `[x]` Update `ArtifactEngagementRepository.kt` to log to `artifact_plays`
- `[x]` Update `ArtifactRepository.kt` and `FeedViewModel.kt` to support `artifactId` in `recordPlay`

## Firestore Security Rules
- `[x]` Update `firestore.rules` for `artifact_plays` and lock down aggregate fields

## Cloud Functions Implementation
- `[x]` Implement `onCommentCreated` aggregate trigger
- `[x]` Implement `onCommentDeleted` aggregate trigger
- `[x]` Implement `onPlayCreated` aggregate trigger
- `[x]` Update `onArtifactCleanupTrigger` for cascading deletes

## Verification
- `[/]` Run Cloud Function Integration Tests
- `[ ]` Run Repository Unit Tests
- `[ ]` Run Firestore Security Rules Tests
