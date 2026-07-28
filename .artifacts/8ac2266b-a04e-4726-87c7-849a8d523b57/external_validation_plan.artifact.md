# RC1 External Validation Plan: Artifact

This document outlines the strategy for controlled external testing of Artifact RC1. The objective is to validate reliability, stability, and "Calm UX" principles in real-world scenarios before the production release.

## Executive Summary
Artifact RC1 has passed all engineering audits. We now move to **External Validation** to stress-test the "Startup Island Architecture," the "On-Demand Recommendation" model, and the "Listen Before You Respond" (LBYR) engagement system. This 72-hour validation cycle focuses on catching "in-the-wild" edge cases that static analysis and emulators cannot simulate.

---

## Testing Strategy
- **Phase**: Alpha (Controlled Group)
- **Duration**: 72 Hours
- **Methodology**: Exploratory testing following a structured checklist + long-duration background monitoring.
- **Tools**: Firebase Crashlytics (Real-time), Google Play Console (Internal App Sharing), Logcat (for local debugging).

---

## Tester Profiles
| Group | Profile | Goal |
| :--- | :--- | :--- |
| **Creators** | Daily journalers, expressive storytellers. | Validate recording stability, publishing reliability, and draft recovery. |
| **Listeners** | Minimalist content consumers, empathy-driven. | Validate feed quality, "On-Demand" transparency, and playback performance. |
| **Edge Testers** | Tech-savvy, low-bandwidth, or low-memory devices. | Stress-test offline sync, backup/restore, and background worker reliability. |

---

## Test Checklist

### 1. Registration & Onboarding
- [ ] Seamless Google ID / Credential Manager sign-in.
- [ ] Sigil (Identity) generation and initial emotion selection.
- [ ] Proper handling of "Startup Arrival" sequence (no blank screens).

### 2. Recording & Publishing
- [ ] Waveform responsiveness during live recording.
- [ ] Transition from recording -> studio -> publishing.
- [ ] **Ambient Upload**: Verify upload continues if the app is put in background.

### 3. Drafts & Recovery
- [ ] Create a draft, force-close app, verify draft persists.
- [ ] Delete a draft; verify it is "released" (deleted from local DB and cache).
- [ ] Verify `PublishingRecoveryWorker` resumes interrupted uploads after 1 hour.

### 4. Home Feed (For You & Recent)
- [ ] **On-Demand Transparency**: Tap "Why this Artifact?" on a recommendation. Verify correct explanation.
- [ ] Verify labels are **absent** from the Recent feed and Discovery items.
- [ ] Emotion filter responsiveness (staggered hydration of filtered results).

### 5. Playback & Engagement
- [ ] ExoPlayer background lifecycle (audio continues when screen off).
- [ ] **LBYR Enforcement**: Verify comments are locked until 95% of the artifact is heard.
- [ ] Reaction (Resonate) synchronization across devices.

### 6. Security & Privacy
- [ ] Identity Reveal flow (if applicable).
- [ ] Stealth mode toggle verification.
- [ ] Encrypted backup to Firebase Storage.

---

## Long-Duration Validation Plan (24–72 Hours)
| Scenario | Focus Area | Requirement |
| :--- | :--- | :--- |
| **Offline Persistence** | Sync Logic | Record 3 artifacts offline. Reconnect after 6 hours. Verify all sync successfully. |
| **Background Maintenance**| WorkManager | Monitor `CleanupOrphanFilesWorker` (24h). Verify no temporary audio files remain in storage. |
| **Memory Pressure** | App Longevity | Keep app in background for 12 hours with other apps active. Re-open; verify "Startup Arrival" is smooth. |
| **Notification Reliability** | Engagement | Verify `ReminderWorker` triggers a "daily reflection" notification exactly 24h after the last activity. |

---

## Bug Reporting Process
All findings must be reported using the following template:

```markdown
### [BUG] Short Descriptive Title
**Severity**: [Critical / High / Medium / Low]
**Device**: [e.g., Pixel 8, Galaxy S21]
**Android Version**: [e.g., 14, 15]
**Build**: [RC1-v1.0.0]

**Steps to Reproduce**:
1. ...
2. ...

**Expected**: [What should have happened]
**Actual**: [What actually happened]

**Logs/Media**: [Link to Crashlytics / Attached Logcat / Screenshot]
```

---

## RC1 Exit Criteria
To promote RC1 to Production, the following criteria must be met:
1. **Zero Critical Issues**: No crashes, data loss, or security breaches in the 72h window.
2. **Sync Reliability**: 100% success rate for `InteractionSyncWorker` on stable connections.
3. **Calm UX Baseline**: 90% of testers report the feed feels "uncluttered" and the "On-Demand" model is clear.
4. **Performance**: Startup to Feed ready in under 3 seconds on mid-range devices.

---

## Production Go / No-Go Framework
- **GO**: All Exit Criteria met. All "High" severity UI bugs fixed.
- **NO-GO**: Any data loss bug, any Play Integrity failure, or >2 High-severity UX regressions.
- **RC2 REQUIRED**: If blocking issues are found, RC1 is discarded, fixes applied, and a new 48h RC2 cycle is initiated.

---

## Recommended Next Milestone
**Production Release (v1.0.0)**
*Contingent on passing this External Validation Plan.*
