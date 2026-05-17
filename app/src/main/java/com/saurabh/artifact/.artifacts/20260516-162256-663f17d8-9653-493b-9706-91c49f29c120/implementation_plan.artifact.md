# Feed System Audit & Refactor: Anonymous Emotional Safety

This plan addresses critical privacy, UX, and technical issues in the "myArtifact" feed, aligning it with the core principle of **anonymous emotional safety**.

## User Review Required

> [!IMPORTANT]
> **Data Migration**: Real names and emails are currently stored in Firestore. This plan includes a migration strategy. Once implemented, existing real names in the `artifacts` and `users` collections should be scrubbed.
> **Anonymous Identity Lifecycle**: We propose generating anonymous identities at first sign-in. Users will NOT be able to set their own names to prevent doxxing and maintain the "safe" aesthetic.

## Proposed Changes

### 1. Anonymous Identity System (Priority 1)

Eliminate real-world identifiers across the platform.

#### [User.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/User.kt)
- Deprecate `displayName` and `email` for public display.
- Add `anonymousName`, `avatarColor`, and `emotionalProfile`.

#### [UserRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/UserRepository.kt)
- Update `getOrCreateProfile` to generate anonymous names using `NameGenerator`.
- Assign a random soft `avatarColor` from a predefined safe palette.

#### [PublishingWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/PublishingWorker.kt)
- Replace `user.displayName` with `userProfile.anonymousName` when creating Firestore documents.

---

### 2. Audio Playback Fix & Architecture (Priority 2)

Standardize ExoPlayer integration and improve reliability.

#### [AudioPlayer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/AudioPlayer.kt)
- Implement robust error handling for invalid/expired URLs.
- Add explicit buffering state tracking for UI feedback.

#### [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt)
- Add playback state validation before calling `audioPlayer.play()`.

---

### 3. Reaction Chip Refactor (Priority 3)

Fix the vertical collapsing layout using `FlowRow`.

#### [ArtifactCard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/components/ArtifactCard.kt)
- Replace `Row` with `FlowRow` for reaction chips.
- Soften the visual style of chips to feel more "gentle".

---

### 4. Safety & Moderation (Priority 4 & 5)

Establish trust through transparent but safe reporting.

#### [ReportSheet.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/moderation/ReportSheet.kt)
- Implement a multi-step reporting flow.
- Add categories: Harassment, Hate speech, Self-harm risk, Sexual content, Spam.

#### [ArtifactCard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/components/ArtifactCard.kt)
- Disable the Comment button and show a "Conversations coming soon" tooltip or snackbar.

---

### 5. Feed Card Redesign (Priority 6)

Visual overhaul for emotional clarity and calmness.

#### [ArtifactCard.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/components/ArtifactCard.kt)
- **Top**: Anonymous avatar (colored circle) + Anonymous Name + Timestamp.
- **Center**: Title + Minimalist Waveform + Centered Play/Pause.
- **Bottom**: FlowRow Reactions + Comment (Disabled) + Save.

## Verification Plan

### Automated Tests
- `UserRepositoryTest`: Verify that `getOrCreateProfile` never leaks Google `displayName`.
- `ArtifactRepositoryTest`: Ensure published artifacts use the user's `anonymousName`.

### Manual Verification
1.  **Identity Audit**: Inspect Firestore `artifacts` collection to ensure no real names are present in new entries.
2.  **Playback Test**: Verify audio starts immediately on card click across various network conditions.
3.  **Layout Stress Test**: Add 10+ reactions to an artifact and verify they wrap gracefully using `FlowRow`.
4.  **Reporting Flow**: Open the report modal and verify all categories are selectable and submit successfully.
