# Replace Profile Icon with User Avatar on Homepage

The goal is to replace the generic profile icon in the homepage top bar with the user's actual profile avatar (Auric or Cartoon based on their configuration).

## Proposed Changes

### [Feed Component]

Update the top bar in the feed screen to display the user's avatar.

#### [FeedScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedScreen.kt)

- Import `com.saurabh.artifact.ui.theme.ArtifactTheme` and `com.saurabh.artifact.ui.components.ArtifactAvatar`.
- In `FeedTopBar`, replace the `Icon(Icons.Rounded.Person, ...)` with `ArtifactAvatar` if a user profile is available.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTopBar(
    onNavigateToNotifications: () -> Unit,
    onNavigateToProfile: () -> Unit
) {
    val currentUser = com.saurabh.artifact.ui.theme.ArtifactTheme.currentUser

    CenterAlignedTopAppBar(
        title = {
            com.saurabh.artifact.ui.components.BrandTitle(
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.alpha(0.8f)
            )
        },
        actions = {
            IconButton(onClick = onNavigateToNotifications) {
                Icon(Icons.Rounded.Notifications, contentDescription = "Echoes")
            }
            IconButton(onClick = onNavigateToProfile) {
                if (currentUser != null) {
                    com.saurabh.artifact.ui.components.ArtifactAvatar(
                        config = currentUser.avatarConfig,
                        size = 32.dp,
                        isStatic = true
                    )
                } else {
                    Icon(Icons.Rounded.Person, contentDescription = "Inner Space")
                }
            }
        },
        // ...
    )
}
```

## Verification Plan

### Manual Verification
- Deploy the app to a device or emulator.
- Log in and set a profile avatar in the "Presence Builder" or "Avatar Editor".
- Navigate to the Home (Feed) screen.
- Verify that the top right profile button shows the user's selected avatar instead of the default person icon.
- Verify that clicking the avatar still navigates to the profile screen.
