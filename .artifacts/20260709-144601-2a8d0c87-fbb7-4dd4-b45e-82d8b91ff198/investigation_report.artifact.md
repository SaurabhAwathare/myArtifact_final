# Investigation Report: Public Profile Published Artifacts

## 1. Problem Statement
Another user's public profile does not display their published Artifacts, despite those Artifacts being visible in the Home Feed. The profile screen opens correctly and shows the user's name, but the "Published" list remains empty.

## 2. Expected Behavior
When a Listener taps on a creator's avatar in the Home Feed, the creator's profile should open and display every Artifact that creator has published that is publicly visible (`isPublic == true` and `status == ACTIVE`).

## 3. Actual Code Path
The execution path for fetching profile artifacts is as follows:

1. **Feed**: [FeedScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedScreen.kt) -> `ArtifactCard` detects avatar click and calls `onAuthorClick(artifact.userId)`.
2. **Navigation**: [FeedNavigation.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/navigation/features/FeedNavigation.kt) -> `navController.navigate(Profile(userId))`.
3. **ViewModel**: [ProfileViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/ProfileViewModel.kt) -> `setTargetUser(userId)` updates `_targetUserId`.
4. **UseCase**: [GetProfileDataUseCase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/profile/GetProfileDataUseCase.kt) -> Calls `artifactRepository.getUserArtifacts(finalId, onlyActive = true)`.
5. **Repository**: [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt) -> Executes a Firestore query on the `artifacts` collection.

## 4. userId Flow Analysis
- **Source**: The `userId` is pulled directly from the `Artifact` object rendered in the Home Feed (`artifact.userId`).
- **Propagation**: The ID is passed correctly through the Compose Navigation route `Profile(userId)` and injected into the `ProfileViewModel`.
- **Target**: The `GetProfileDataUseCase` receives this ID and passes it to the repository.
- **Verdict**: The `userId` flow is correct and there is no mismatch between the Feed and the Profile ID.
- **Confidence Level**: Level 2 — Code Evidence.

## 5. Firestore Query Analysis
The query executed in `ArtifactRepository.getUserArtifacts(userId, onlyActive = true)` is:

```kotlin
firestore.collection("artifacts")
    .whereEqualTo("userId", userId)
    .whereEqualTo("status", "ACTIVE")
    .orderBy("createdAt", Query.Direction.DESCENDING)
```

**Required Index:**
This query requires a composite index on:
- Collection: `artifacts`
- Fields: `userId` (Asc/Desc), `status` (Asc/Desc), `createdAt` (Desc)

## 6. Feed vs Profile Query Comparison

| Feature | Home Feed Query | Public Profile Query |
| :--- | :--- | :--- |
| **Collection** | `artifacts` | `artifacts` |
| **User Filter** | None | `userId == targetId` |
| **Status Filter** | `status == "ACTIVE"` | `status == "ACTIVE"` |
| **Privacy Filter**| `isPublic == true` | **None** |
| **Ordering** | `createdAt` DESC | `createdAt` DESC |

### Critical Discrepancy
The Public Profile query **fails to filter by `isPublic == true`**. In Firestore, a query for someone else's data that does not explicitly filter for public-only content will be rejected by security rules (Permission Denied) if the rules protect private documents.

## 7. Error Handling Analysis
In [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt) line 282:
```kotlin
        val subscription = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(emptyList()) // <--- ROOT CAUSE OF INVISIBILITY
                return@addSnapshotListener
            }
```
The repository **swallows the Firestore error** and returns an empty list. This masks critical failures like "Missing Index" or "Permission Denied".

## 8. Firestore Index Analysis
- The Home Feed query likely has an index for `(isPublic, status, createdAt)`.
- The Public Profile query requires a **different** index: `(userId, status, createdAt)`.
- If the developer only tested their own profile, they used a different query (`onlyActive = false`) which only requires `(userId, createdAt)`, a standard index.
- The failure only occurs when viewing **others'** profiles due to the added `status == "ACTIVE"` filter.

## 9. Most Likely Root Cause
**Primary Cause**: Security Rule Violation. The query lacks the `.whereEqualTo("isPublic", true)` filter. Firestore security rules reject this query because it *could* return private artifacts, even if the user has none.
**Secondary Cause**: Missing Composite Index. The query `(userId, status, createdAt DESC)` likely lacks the required composite index in the Firebase Console.
**Masking Factor**: Error swallowing in `ArtifactRepository.getUserArtifacts` prevents the error from appearing in logs or the UI.

## 10. Confidence Level
**Level 2 — Code Evidence**. The code path and query structure clearly show the missing privacy filter and the error-swallowing behavior, which aligns perfectly with the symptoms.

## 11. Runtime Logging Plan
If verification is needed, update `ArtifactRepository.kt`:
```diff
         val subscription = query.addSnapshotListener { snapshot, error ->
             if (error != null) {
+                diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "USER_ARTIFACTS_FETCH_FAILED", mapOf("userId" to userId), error)
                 trySend(emptyList())
                 return@addSnapshotListener
             }
```

## 12. Recommended Next Action
1. **Fix Query**: Add `.whereEqualTo("isPublic", true)` to the query in `ArtifactRepository.getUserArtifacts` when querying another user's profile.
2. **Create Index**: Add a composite index in the Firebase Console for:
   `Collection: artifacts, Fields: userId: ASC, isPublic: ASC, status: ASC, createdAt: DESC`
3. **Improve Logging**: Stop swallowing the error in `getUserArtifacts` and use the `diagnosticLogger` to capture the failure.
