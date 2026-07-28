# Production Release Playbook: Artifact v1.0.0

This playbook is the operational runbook for the public launch of Artifact v1.0.0. It defines the specific steps, timelines, and safety protocols required to transition from a Release Candidate to a stable Production environment.

## 1. Executive Summary
Artifact v1.0.0 is ready for production rollout. This launch will utilize a **staged rollout** (starting at 10%) to mitigate risks associated with the new "Startup Island Architecture" and "On-Demand Recommendation" model.

---

## 2. Pre-Launch Checklist (T-Minus 24 Hours)
*Final verification of the production environment.*

- [ ] **Release Bundle**: Verify production AAB is built with `isMinifyEnabled = true` and signed with the production keystore.
- [ ] **Firebase Hardening**:
    - [ ] Deploy latest `firestore.rules` and `storage.rules`.
    - [ ] Deploy production Cloud Functions (`functions/src/index.ts`).
    - [ ] Confirm App Check is enforced for Firestore and Storage.
- [ ] **Infrastructure**:
    - [ ] Verify Firestore indexes are created.
    - [ ] Confirm Google Play Console store listing is approved.
    - [ ] Verify `google-services.json` matches the production Firebase project.
- [ ] **Secrets Audit**: Ensure no development keys or `TODO` markers remain in the production code path.

---

## 3. Launch-Day Timeline
*SOP for the release window.*

| Time | Action | Responsibility |
| :--- | :--- | :--- |
| **09:00 AM** | Final "Go" sync with engineering leads. | Release Manager |
| **10:00 AM** | Upload AAB to Play Console (Production Track). | DevOps / Eng |
| **10:15 AM** | Start Staged Rollout (10%). | Release Manager |
| **10:30 AM** | Verify monitoring dashboards (Firebase/Vitals). | SRE / Monitoring |
| **11:00 AM** | **First Hour Review**: Analyze early telemetry. | Full Team |
| **04:00 PM** | **End of Day Review**: Evaluate P0/P1 incidents. | Leadership |

---

## 4. Monitoring Schedule

### Phase 1: The Golden Hour (Every 15 Minutes)
- **Stability**: Crash-free users > 99.9%.
- **Connectivity**: Authentication success rate.
- **Performance**: `total_ms` startup trace < 3.0s.
- **Critical Path**: Publishing and Playback success events.

### Phase 2: Launch Day (Every 4 Hours)
- **Vitals**: Slow rendering and frozen frame rates.
- **Backend**: Cloud Function execution errors and latency.
- **Support**: Triage Play Store reviews and direct feedback emails.
- **Integrity**: Monitor `InteractionSyncWorker` success logs.

---

## 5. Rollback Procedure
*If a critical failure is detected, trigger the following protocols:*

### Rollback Triggers
- **P0 Incident**: App crashes on startup for >1% of users.
- **Data Integrity**: Evidence of lost Artifacts or corrupt recordings.
- **Security**: Play Integrity rejection of legitimate users > 20%.

### Execution Steps
1. **Play Console**: Halt the staged rollout immediately.
2. **Firebase**: Revert to the previous stable Security Rules if necessary.
3. **Communication**: Trigger the "Incident Communication Plan" (see Section 6).
4. **Analysis**: Begin post-mortem for RC2/v1.0.1 hotfix.

---

## 6. Incident Communication Plan
- **Internal**: Real-time updates via the `#eng-release-v1` channel.
- **External**: Update the "Rescue Screen" or Status Page if backend services are impacted.
- **Log Template**:
    - **Incident ID**: [YYYYMMDD-001]
    - **Status**: [Investigating / Identified / Resolved]
    - **Impact**: [% of users / Specific device models]
    - **Workaround**: [e.g., Clear cache / Logout-Login]

---

## 7. Launch Completion Checklist
- [ ] 100% Rollout completed.
- [ ] 0 Open P0/P1 incidents.
- [ ] Crash-free session rate stabilized at target.
- [ ] Performance benchmarks verified in the wild.
- [ ] **Official Release Complete**.

---

## 8. Official Production Release Sign-Off

> [!IMPORTANT]
> **GO / NO-GO**: [APPROVED]
> **Version**: 1.0.0
> **Date**: 2026-07-31

**Final Release Lead**: ____________________
