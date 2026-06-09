# Task: Enhance Device Integrity and Tamper Protection

- [/] Research and Planning
    - [x] Analyze `IdentityScout` and current security implementation
    - [x] Identify gaps in root/tamper detection
    - [x] Create implementation plan
- [ ] Implement `IntegrityScout`
    - [ ] Create `IntegrityScout.kt` with root/emulator detection
    - [ ] Create `IntegrityScoutTest.kt` for verification
- [ ] Integrate with Startup and ViewModel
    - [ ] Update `StartupCoordinator` to run integrity checks
    - [ ] Expose integrity status in `MainViewModel`
    - [ ] Add "Safe Mode" logic to `UploadGuard`
- [ ] Verification
    - [ ] Run unit tests
    - [ ] Manual verification on emulator
