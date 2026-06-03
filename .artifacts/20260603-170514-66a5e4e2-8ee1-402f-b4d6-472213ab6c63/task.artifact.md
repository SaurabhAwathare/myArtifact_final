# Task: Hardening the Report & Moderation System

## Current Status
- [ ] Research & Plan System Hardening
- [ ] Synchronize Comment Models
- [ ] Implement Threshold Filtering for Comments
- [ ] Refine Device ID for Anonymous Reporting
- [ ] Verify Moderation Logic

## Subtasks
- [ ] Update `ArtifactComment` model with `reportCount`
- [ ] Update `CommentPagingSource` to filter reported content
- [ ] Improve `deviceId` generation in `AuthRepository` or `ArtifactRepository`
- [ ] Add unit tests for report threshold logic
