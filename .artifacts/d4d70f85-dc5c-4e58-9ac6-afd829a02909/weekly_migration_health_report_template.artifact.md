# Migration Health Report (Template)

**Reporting Period**: [YYYY-MM-DD] to [YYYY-MM-DD]
**Stabilization Day**: [X/30]

## Executive Summary
[Brief overview of migration status and any critical issues found this week.]

## Migration Metrics

| Metric | Current Week | Total (Since Phase 1) | Trend |
| :--- | :--- | :--- | :--- |
| `USER_PROFILE_REPAIR_COMPLETED` | 0 | [Total] | [Up/Down/Zero] |
| `USER_PROFILE_NORMALIZED` | 0 | [Total] | [Up/Down/Zero] |
| `LEGACY_AVATAR_FIELDS_EXERCISED` | 0 | [Total] | [Up/Down/Zero] |
| Migration-related Crashes | 0 | [Total] | [Stable/Regression] |

## Error Monitoring (Crashlytics)

### [Service Name] (e.g., ProfileRepairService)
- **Error**: [Error Name]
- **Occurrences**: [X]
- **Impact**: [High/Med/Low]
- **Status**: [Investigating/Known/Fixed]

## Unexpected Avatar Code Paths Exercised
[List any instances where legacy avatar fields were accessed and why.]

1. [Path 1]: [Reason]
2. [Path 2]: [Reason]

## Sigil Integrity Checks
- **User Reports**: [None / List Issues]
- **Visual Audit**: [Pass/Fail]

## Recommendation
- [Continue Stabilization / Investigate Defect / Proceed to Phase 2 (only if Day 30+)]
