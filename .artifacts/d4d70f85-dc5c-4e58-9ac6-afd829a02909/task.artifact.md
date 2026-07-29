# Production Stabilization Setup Tasks

- [x] Update `UserRepository.kt` logging for `legacyFieldsRemoved=true` and `INFO` normalization.
- [x] Inject `DiagnosticLogger` into `ProfileRepairService.kt`.
- [x] Replace `Log` calls in `ProfileRepairService.kt` with `DiagnosticLogger` and standardized `IDENTITY_COMPATIBILITY_PATH_USED`.
- [x] Inject `DiagnosticLogger` into `UserSessionManager.kt`.
- [x] Add `IDENTITY_COMPATIBILITY_PATH_USED` for DataStore fallbacks in `UserSessionManager.kt`.
- [x] Verify logging changes in a local debug session.
- [ ] (Ongoing) Monitor Crashlytics for migration failures.
- [ ] (Weekly) Generate Migration Health Report.
- [ ] (Day 30) Generate Production Stability Report.
