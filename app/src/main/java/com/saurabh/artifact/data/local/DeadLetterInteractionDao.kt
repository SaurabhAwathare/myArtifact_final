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
    NOT_FOUND_OR_ALREADY_PROCESSED,
    IDENTITY_MISMATCH,
    INVALID_PAYLOAD
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

    @Query("SELECT * FROM dead_letter_interactions WHERE userId = :userId ORDER BY failedAt DESC")
    abstract suspend fun getAll(userId: String): List<DeadLetterInteractionEntity>

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

    @Query("SELECT * FROM dead_letter_interactions WHERE id = :id AND userId = :userId")
    abstract suspend fun getByIdAndUser(id: Long, userId: String): DeadLetterInteractionEntity?

    @Query("DELETE FROM dead_letter_interactions WHERE id = :id AND userId = :userId")
    abstract suspend fun deleteByIdAndUser(id: Long, userId: String): Int

    @Query("DELETE FROM dead_letter_interactions WHERE failedAt < :timestamp AND userId = :userId AND failureType = 'PERMANENT'")
    abstract suspend fun deleteOldPermanentDeadLetters(timestamp: Long, userId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPending(pending: PendingInteractionEntity): Long

    @Query("SELECT COUNT(*) FROM pending_interactions WHERE correlationId = :correlationId")
    abstract suspend fun countPendingByCorrelationId(correlationId: String): Int

    @Query("SELECT * FROM pending_interactions WHERE userId = :userId AND correlationId = :correlationId")
    abstract suspend fun getPendingByUserIdAndCorrelationId(userId: String, correlationId: String): PendingInteractionEntity?

    @Transaction
    open suspend fun performAtomicRecovery(
        userId: String,
        deadLetterId: Long,
        reconstructedPending: PendingInteractionEntity
    ): DlqRecoveryResult {
        // 1. Validation before any mutation
        if (userId.isBlank()) return DlqRecoveryResult.INVALID_PAYLOAD
        if (reconstructedPending.correlationId.isBlank()) return DlqRecoveryResult.INVALID_PAYLOAD
        if (reconstructedPending.userId != userId) return DlqRecoveryResult.IDENTITY_MISMATCH

        // 2. Retrieve original DLQ record using getByIdAndUser & verify owner
        val dlqRecord = getByIdAndUser(deadLetterId, userId)
            ?: return DlqRecoveryResult.NOT_FOUND_OR_ALREADY_PROCESSED

        if (dlqRecord.userId != userId) return DlqRecoveryResult.IDENTITY_MISMATCH

        // 3. Compare all seven immutable logical identity fields
        if (!matchesLogicalIdentity(dlqRecord, reconstructedPending)) {
            return DlqRecoveryResult.IDENTITY_MISMATCH
        }

        // 4. Retrieve actual existing pending record using userId and correlationId
        val existingPending = getPendingByUserIdAndCorrelationId(userId, reconstructedPending.correlationId)

        if (existingPending != null) {
            // Verify all seven logical identity fields between DLQ record and existing pending record
            if (!matchesLogicalIdentity(dlqRecord, existingPending)) {
                return DlqRecoveryResult.IDENTITY_MISMATCH
            }

            // Delete DLQ record and verify deletion row count
            val deletedRows = deleteByIdAndUser(deadLetterId, userId)
            if (deletedRows == 1) {
                return DlqRecoveryResult.DUPLICATE_REMOVED
            } else {
                throw IllegalStateException("Transaction rollback required: expected 1 DLQ row deleted for duplicate removal, but deleted $deletedRows")
            }
        }

        // 5. New pending interaction (when existingPending == null)
        val rowId = insertPending(reconstructedPending)

        if (rowId != -1L) {
            // Insertion succeeded: delete DLQ record and verify deletion row count
            val deletedRows = deleteByIdAndUser(deadLetterId, userId)
            if (deletedRows == 1) {
                return DlqRecoveryResult.SUCCESS
            } else {
                throw IllegalStateException("Transaction rollback required: pending interaction inserted (rowId=$rowId), but DLQ record deletion affected $deletedRows rows (expected 1)")
            }
        } else {
            // Insertion returned -1L due to constraint conflict; retrieve conflicting pending record
            val conflictingPending = getPendingByUserIdAndCorrelationId(userId, reconstructedPending.correlationId)
            if (conflictingPending != null && matchesLogicalIdentity(dlqRecord, conflictingPending)) {
                val deletedRows = deleteByIdAndUser(deadLetterId, userId)
                if (deletedRows == 1) {
                    return DlqRecoveryResult.DUPLICATE_REMOVED
                } else {
                    throw IllegalStateException("Transaction rollback required: DLQ deletion failed after insert conflict (affected $deletedRows rows)")
                }
            } else {
                // Field mismatch or unresolvable conflict; preserve DLQ record
                return DlqRecoveryResult.IDENTITY_MISMATCH
            }
        }
    }

    @Transaction
    open suspend fun performAtomicRecovery(
        deadLetterId: Long,
        reconstructedPending: PendingInteractionEntity
    ): DlqRecoveryResult {
        return performAtomicRecovery(reconstructedPending.userId, deadLetterId, reconstructedPending)
    }

    @Transaction
    open suspend fun recoverToPending(
        pending: PendingInteractionEntity,
        deadLetterId: Long
    ): DlqRecoveryResult {
        return performAtomicRecovery(pending.userId, deadLetterId, pending)
    }

    @Transaction
    open suspend fun recoverToPending(
        userId: String,
        pending: PendingInteractionEntity,
        deadLetterId: Long
    ): DlqRecoveryResult {
        return performAtomicRecovery(userId, deadLetterId, pending)
    }

    @Query("SELECT * FROM dead_letter_interactions WHERE artifactId = :artifactId AND userId = :userId AND interactionType = :interactionType ORDER BY failedAt DESC")
    abstract fun observeDeadLettersForArtifact(
        artifactId: String,
        userId: String,
        interactionType: String = InteractionType.COMMENT
    ): Flow<List<DeadLetterInteractionEntity>>

    companion object {
        fun matchesLogicalIdentity(
            dlq: DeadLetterInteractionEntity,
            pending: PendingInteractionEntity
        ): Boolean {
            return dlq.userId == pending.userId &&
                    dlq.artifactId == pending.artifactId &&
                    dlq.interactionType == pending.interactionType &&
                    dlq.action == pending.action &&
                    dlq.metadata == pending.metadata &&
                    dlq.createdAt == pending.createdAt &&
                    dlq.correlationId == pending.correlationId
        }
    }
}
