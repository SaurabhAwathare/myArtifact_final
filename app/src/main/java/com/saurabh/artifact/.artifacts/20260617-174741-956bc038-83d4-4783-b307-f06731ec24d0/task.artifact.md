# Task Management

- [x] Implement fix for navigation loop
	- [x] Modify `PlayerViewModel` to guard `navigateToPublish` emission
	- [x] Add defensive check to `GlobalOverlayHost`
- [x] Verify fix
	- [x] Scenario 1: Play a published artifact from Feed (No navigation)
	- [x] Scenario 2: Play your own draft (Navigate to Studio when threshold reached)
	- [x] Scenario 3: Reopen already published artifact multiple times (No navigation)
	- [x] Scenario 4: Background → foreground while playing (No sudden navigation)
	- [x] Ensure `navigateToPublish` is not emitted for `PUBLISHED` artifacts
	- [x] Verify `PublishingStudio` navigation still works for drafts
