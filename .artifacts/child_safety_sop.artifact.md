# Artifact — Child Safety & CSAM Reporting SOP (Manual)

This document establishes the minimum legally appropriate Standard Operating Procedure (SOP) for handling Child Sexual Abuse Material (CSAM) and child safety concerns on Artifact, compliant with **Google Play Policies** and the **Indian Information Technology (Intermediary Guidelines) Rules, 2021**.

## 1. Governance & Contact
- **Grievance Officer:** The Artifact Developer / Admin.
- **Support Channel:** `supportartifact@gmail.com`
- **Response Deadline:** Content involving CSAM or explicit sexual acts must be disabled within **24 hours** of receipt of a report (Indian IT Rules 2021).

## 2. Step-by-Step Reporting Flow

### Step 1: Receipt of Report
- Reports are received via the in-app `CHILD_SAFETY` category.
- Automated Cloud Functions aggregate these reports into the `moderation_queue` collection.

### Step 2: Immediate Takedown (Pending Review)
- Upon receiving a `CHILD_SAFETY` report, the Admin must immediately use the **"Hide Artifact"** action in the Moderation Dashboard.
- This disables access for all users while preserving the data for verification.

### Step 3: Human Verification
- The Admin reviews the reported Artifact (audio/title).
- **Classification A:** Confirmed CSAM or Grooming.
- **Classification B:** Misclassified (e.g., general sexual content or spam).
- **Classification C:** False report.

### Step 4: Evidence Preservation
- **DO NOT PERMANENTLY DELETE** confirmed CSAM immediately.
- Legal authorities require the preservation of information (metadata, IP logs, files) for at least **180 days** to support investigations.
- Artifact's "Hide" status (setting `isPublic: false`) satisfies this preservation requirement without exposing the material.

### Step 5: Authority Reporting
If Classification A (Confirmed CSAM) is reached, the Admin must manually report the incident:
1.  **Global:** Submit a report to the **NCMEC CyberTipline** at [https://report.cybertip.org/](https://report.cybertip.org/).
2.  **Regional (India):** Submit a report to the **National Cyber Crime Reporting Portal** at [https://cybercrime.gov.in/](https://cybercrime.gov.in/).

### Step 6: Enforcement
- **Content:** The Artifact is permanently removed from the public "Human Library."
- **Account:** The associated Artifact Creator account is permanently terminated.
- **Platform:** The user's Google UID and device hash are added to an internal blacklist.

## 3. Prerequisite Code Changes
To make this SOP operational, the following minimal changes are required:
1.  **Enum Update:** Add `CHILD_SAFETY` to `ReportReason` in `ModerationModels.kt`.
2.  **UI Update:** Add "Child Safety" to `ReportSheet.kt` with a high-priority icon.
3.  **Dashboard Update:** Highlight `CHILD_SAFETY` reports in the `ModerationScreen.kt` for 24-hour compliance.
