# Task: Firestore Security Model Refactoring (Phase 1)

## Setup & Rules
- [x] Update `firestore.rules` for Release A (Allow owner to delete sensitive fields from root)
- [x] Verify `admin_rules.test.js` updates

## Implementation
- [x] Implement Atomic Lazy Migration in `UserRepository.getOrCreateProfile`
- [x] Update `FCMService.kt` token path
- [x] Audit `User.kt` documentation and property usage
- [x] Ensure `SettingsRepository.kt` sync safety

## Verification
- [x] Integration Test: Atomic Migration & Idempotency
- [x] Manual Test: Legacy User Field Migration
- [x] Manual Test: FCM Token Refresh Path
