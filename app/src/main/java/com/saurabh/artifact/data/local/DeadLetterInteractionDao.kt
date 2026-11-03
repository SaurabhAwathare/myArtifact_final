package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

enum class DlqRecoveryResult {
    SUCCESS,
    DUPLICATE_REMOVED,
    NOT_FOUND_OR_ALREADY_PROCESSED
}

@Dao
abstract class DeadLetterInteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(interaction: DeadLetterInteractionEntity)

    @Delete
    abstract suspend fun deletePending(pending: PendingInteractionEntity)

    @Transaction
    open suspend fun moveToDeadLetter(
        deadLetter: DeadLetterInteractionEntity,
        pending: PendingInteractionEntity
    ) {
        insert(deadLetter)
        deletePending(pending)
    }

    @Query("SELECT * FROM dead_letter_interactions ORDER BY failedAt DESC")
    abstract suspend fun getAll(): List<DeadLetterInteractionEntity>

    @Query("SELECT * FROM dead_letter_interactions WHERE userId = :userId AND interactionType = :interactionType ORDER BY failedAt ASC")
    abstract suspend fun getDeadLettersForUser(
        userId: String,
        interactionType: String = InteractionType.COMMENT
    ): List<DeadLetterInteractionEntity>

    @Query("SELECT * FROM dead_letter_interactions WHERE userId = :userId AND interactionType = :interactionType AND failureType = 'RETRY_LIMIT_EXCEEDED' AND failedAt <= :maxFailedAt ORDER BY failedAt ASC")
    abstract suspend fun getEligibleDeadLettersForRecovery(
        userId: String,
        interactionType: String = InteractionType.COMMENT,
        maxFailedAt: Long
    ): List<DeadLetterInteractionEntity>

    @Query("SELECT * FROM dead_letter_interactions WHERE id = :id")
    abstract suspend fun getById(id: Long): DeadLetterInteractionEntity?

    @Query("DELETE FROM dead_letter_interactions WHERE id = :id")
    abstract suspend fun deleteById(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPending(pending: PendingInteractionEntity): Long

    @Query("SELECT COUNT(*) FROM pending_interactions WHERE correlationId = :correlationId")
    abstract suspend fun countPendingByCorrelationId(correlationId: String): Int

    @Transaction
    open suspend fun performAtomicRecovery(
        deadLetterId: Long,
        reconstructedPending: PendingInteractionEntity
    ): DlqRecoveryResult {
        // 1. Verify DLQ record physically exists in Room
        if (getById(deadLetterId) == null) return DlqRecoveryResult.NOT_FOUND_OR_ALREADY_PROCESSED

        // 2. Perform duplicate detection inside authoritative transaction using correlationId
        val correlationId = reconstructedPending.correlationId
        val existingPendingCount = if (correlationId.isNotBlank()) countPendingByCorrelationId(correlationId) else 0

        if (existingPendingCount > 0) {
            // Pending record already exists in pending_interactions.
            // Atomically delete duplicate DLQ entry.
            deleteById(deadLetterId)
            return DlqRecoveryResult.DUPLICATE_REMOVED
        }

        // 3. Atomically delete DLQ record and verify deletion row count
        val deletedRows = deleteById(deadLetterId)
        if (deletedRows <= 0) {
            // Concurrent process/thread already processed or deleted this DLQ record
            return DlqRecoveryResult.NOT_FOUND_OR_ALREADY_PROCESSED
        }

        // 4. Insert pending interaction with IGNORE strategy (DB UNIQUE index on correlationId)
        val rowId = insertPending(reconstructedPending)
        if (rowId == -1L) {
            // DB UNIQUE constraint conflict caught: duplicate pending record already exists.
            return DlqRecoveryResult.DUPLICATE_REMOVED
        }

        return DlqRecoveryResult.SUCCESS
    }

    @Transaction
    open suspend fun recoverToPending(
        pending: PendingInteractionEntity,
        deadLetterId: Long
    ) {
        performAtomicRecovery(deadLetterId, pending)
    }

    @Query("SELECT * FROM dead_letter_interactions WHERE artifactId = :artifactId AND userId = :userId AND interactionType = :interactionType ORDER BY failedAt DESC")
    abstract fun observeDeadLettersForArtifact(
        artifactId: String,
        userId: String,
        interactionType: String = InteractionType.COMMENT
    ): Flow<List<DeadLetterInteractionEntity>>
}
