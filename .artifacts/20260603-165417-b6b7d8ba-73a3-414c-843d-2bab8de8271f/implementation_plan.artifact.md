# Implementation Plan: Anonymous Guest Access & Low-Friction Entry

Currently, the app requires users to sign in with Google before they can even browse the "For You" feed. While the app uses a pseudonymized "Anonymous Identity" for display, the technical requirement of a hard login creates high friction and contradicts the "Anonymous Emotional Safety" theme.

This plan transitions the app to use **Firebase Anonymous Authentication** by default. Users will land on the feed as a "Guest" immediately upon app startup, with their personalization (resonances, history) still tied to their device via a Firebase `uid`. Google Sign-In will become an optional "Secure Account" step.

## Proposed Changes

### 1. Authentication Layer Enhancements
Enable the backend to support temporary guest accounts.

#### [AuthRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/AuthRepository.kt)
- Add `signInAnonymously()` to initiate a guest session.
- Add `linkWithGoogle()` to allow guests to upgrade their account without losing their history.

```kotlin
suspend fun signInAnonymously(): Result<FirebaseUser?> {
    return try {
        val result = firebaseAuth.signInAnonymously().await()
        Result.success(result.user)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---

### 2. Startup Sequence Refactor
Eliminate the login wall during app boot.

#### [MainViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)
- Update `start()` to trigger `signInAnonymously()` if `currentUser` is null.
- Ensure the app waits for this initialization before transitioning from the Splash screen to `Home`.

#### [LoginViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/login/LoginViewModel.kt)
- Add a `skipToGuest()` method that triggers anonymous sign-in (as a fallback if auto-login was bypassed).

---

### 3. UI/UX Adaptations
Adjust the navigation and profile to handle "Guest" vs. "Authenticated" states.

#### [NavGraph.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/navigation/NavGraph.kt)
- No longer treat `Login` as the mandatory start destination for unauthenticated users.

#### [ProfileScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/ProfileScreen.kt)
- Display a "Secure Your Presence" card/button for anonymous users.
- This will navigate to the `Login` screen (renamed to "Identity Security" in context).

#### [LoginScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/login/LoginScreen.kt)
- Add a "Browse as Guest" button to the bottom for users who want to explicitly bypass Google Login.
- Update the messaging to emphasize that they are already anonymous, but Google Sign-In enables multi-device sync.

---

## Verification Plan

### Automated Tests
- `AuthRepositoryTest`: Verify `signInAnonymously` returns a valid user.
- `MainViewModelTest`: Mock null user and verify `signInAnonymously` is called during `start()`.

### Manual Verification
1. **Fresh Install**: Open app, verify it goes Splash -> Onboarding -> Home (Feed) without asking for login.
2. **Personalization**: As a guest, "Resonate" with an artifact. Close/reopen app, verify resonance is still there.
3. **Upgrade Path**: Go to Profile -> Secure Account -> Sign in with Google. Verify account is linked (or at least switches successfully).
4. **Offline Resilience**: Verify that if anonymous sign-in fails (no network), the app shows a graceful error state rather than a crash.
