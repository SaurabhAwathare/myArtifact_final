# Database Query Plan Investigation – InteractionSyncWorker

## Problem Statement
The `InteractionSyncWorker` is responsible for synchronizing local interaction events and engagement data with Firestore. Previous investigations into redundant worker executions identified potential database I/O as a performance cost center. This investigation aims to verify if the underlying database queries are optimized via indices or if they perform inefficient full table scans.

## Question Being Answered
**Are the database queries executed by `InteractionSyncWorker` and its associated repositories performing full table scans or indexed lookups?**

## DAO Queries Analyzed

### Table: `pending_interactions`
| DAO Method | SQL Statement | WHERE Clause Columns | Expected Behavior |
| :--- | :--- | :--- | :--- |
| `getPendingForUser` | `SELECT * FROM pending_interactions WHERE userId = :userId ORDER BY createdAt ASC` | `userId` | **FULL TABLE SCAN** |
| `deleteByType` | `DELETE FROM pending_interactions WHERE artifactId = :artifactId AND userId = :userId AND interactionType = :type` | `artifactId`, `userId`, `interactionType` | **FULL TABLE SCAN** |
| `observePendingForArtifact` | `SELECT * FROM pending_interactions WHERE artifactId = :artifactId AND userId = :userId` | `artifactId`, `userId` | **FULL TABLE SCAN** |

### Table: `artifact_engagement`
| DAO Method | SQL Statement | WHERE Clause Columns | Expected Behavior |
| :--- | :--- | :--- | :--- |
| `getEngagementsRequiringSync` | `SELECT * FROM artifact_engagement WHERE syncState = 'PENDING' OR syncState = 'FAILED'` | `syncState` | **FULL TABLE SCAN** |
| `getEngagement` | `SELECT * FROM artifact_engagement WHERE artifactId = :artifactId` | `artifactId` (PK) | **INDEXED (Point Lookup)** |
| `updateSyncStatus` | `UPDATE artifact_engagement SET ... WHERE artifactId = :artifactId` | `artifactId` (PK) | **INDEXED (Point Lookup)** |
| `markAsSynced` | `UPDATE artifact_engagement SET ... WHERE artifactId = :artifactId AND syncState = 'SYNCING'` | `artifactId` (PK), `syncState` | **INDEXED (Point Lookup)** |

### Table: `dead_letter_interactions`
| DAO Method | SQL Statement | WHERE Clause Columns / Order | Expected Behavior |
| :--- | :--- | :--- | :--- |
| `getAll` | `SELECT * FROM dead_letter_interactions ORDER BY failedAt DESC` | `failedAt` (Sort) | **FULL TABLE SCAN + SORT** |

## Entity Indices Discovered

Static analysis of the Room Entities reveals that no indices are defined beyond the Primary Keys.

### [PendingInteractionEntity](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/PendingInteractionEntity.kt)
- **Primary Key**: `id` (Long, Auto-generated)
- **Indices**: None.
- **Risk**: `userId` is used as a filter in almost every query but is not indexed.

### [ArtifactEngagement](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEngagement.kt)
- **Primary Key**: `artifactId` (String)
- **Indices**: None.
- **Risk**: `syncState` is used to sweep for unsynced records. As the number of artifacts the user interacts with grows, the time to find pending syncs will increase linearly (O(N)).

### [DeadLetterInteractionEntity](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/DeadLetterInteractionEntity.kt)
- **Primary Key**: `id` (Long, Auto-generated)
- **Indices**: None.

## Expected Query Behavior (Indexed vs. Table Scan)

| Scenario | Indexed? | Reason |
| :--- | :--- | :--- |
| Fetching pending interactions for the current user | **No** | `userId` in `pending_interactions` lacks an index. |
| Sweeping engagement table for unsynced records | **No** | `syncState` in `artifact_engagement` lacks an index. |
| Updating/Deleting specific records by ID | **Yes** | Uses Primary Key (`id` or `artifactId`). |
| Collapsing redundant events | **No** | Uses `getPendingForUser` which scans the table. |

## Evidence Supporting Conclusions
1.  **Annotation Audit**: The `@Entity` definitions in `PendingInteractionEntity.kt` and `ArtifactEngagement.kt` do not include an `indices` array.
2.  **Property Audit**: No `@Index` annotations are present on individual properties like `userId` or `syncState`.
3.  **DAO Implementation**: The `EngagementDao` and `PendingInteractionDao` queries use these non-indexed columns in `WHERE` clauses.

## Confidence Level
**High**. The absence of indices is confirmed through direct inspection of the source code. Room does not create implicit indices for non-primary key columns used in queries.

## Remaining Unknowns
- **Table Size**: The actual performance impact is proportional to the number of rows. While `pending_interactions` is likely small, `artifact_engagement` can grow significantly over time.
- **SQLite Optimization**: SQLite might use the primary key for partial optimization if it happened to be part of a composite key (not the case here).

## Recommendation
> [!IMPORTANT]
> To avoid linear performance degradation as the local database grows, the following indices should be added in a future schema migration:
> 1. **`pending_interactions`**: Index on `userId`.
> 2. **`artifact_engagement`**: Index on `syncState`.
> 3. **`dead_letter_interactions`**: Index on `failedAt` to support efficient sorting of failure logs.

Do **NOT** add these indices immediately. This report serves as evidence for the necessity of optimization. If the user decides to proceed, a Migration plan will be required.
