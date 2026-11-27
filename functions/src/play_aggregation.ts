import * as admin from "firebase-admin";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { logger } from "./util/logger";
import {
  isValidQualifiedCounter,
  isCounterExhausted,
  validateArtifactPlayEvent,
} from "./util/validation/play_event";

/**
 * 60-day retention window for idempotency records and tombstones.
 */
export const IDEMPOTENCY_EXPIRY_MS = 60 * 24 * 60 * 60 * 1000;

export type AggregationStatus =
  | "SUCCESS"
  | "QUARANTINED"
  | "REJECTED"
  | "ALREADY_PROCESSED";

export interface AggregationOutcome {
  status: AggregationStatus;
  reason?: string;
  playId: string;
  artifactId?: string;
  userId?: string;
  diagnostic?: Record<string, unknown>;
}

export interface AggregationOptions {
  source?: "EVENT_TRIGGER" | "RECONCILIATION";
  nowMs?: number;
  barrierAfterRead?: (tx: admin.firestore.Transaction) => Promise<void>;
}

/**
 * Stage B, C, D, E, F — Authoritative Play Event Aggregation Transaction.
 *
 * Implements the agreed 6-step transactional structure:
 * 1. Read idempotency_keys/{playId}.
 * 2. Read deleted_users/{userId}.
 * 3. Read artifacts/{artifactId}.
 * 4. Complete all eligibility and integrity decisions.
 * 5. Increment qualifiedPlayCount only.
 * 6. Write the idempotency record in the same transaction.
 *
 * All transaction reads strictly precede all transaction writes.
 * The three reads are executed concurrently using Promise.all.
 */
export async function aggregatePlayEventTx(
  db: admin.firestore.Firestore,
  rawPayload: unknown,
  playId: string,
  options?: AggregationOptions
): Promise<AggregationOutcome> {
  const nowMs = options?.nowMs ?? Date.now();
  const expiresAt = Timestamp.fromMillis(nowMs + IDEMPOTENCY_EXPIRY_MS);
  const idempotencyRef = db.collection("idempotency_keys").doc(playId);

  // Stage D: Dedicated independent payload validation BEFORE or OUTSIDE tx.
  // If payload is permanently invalid, durably record rejection so it never triggers retry storms.
  const validationResult = validateArtifactPlayEvent(playId, rawPayload, { nowMs });
  if (!validationResult.valid) {
    logger.error(`[PLAY_AGGREGATE] Validation failed | PlayID=${playId} | Reason=${validationResult.reason}`, {
      field: validationResult.field,
      details: validationResult.details,
    });

    const recordData = (rawPayload && typeof rawPayload === "object") ? (rawPayload as Record<string, unknown>) : {};
    const safeUserId = typeof recordData.userId === "string" ? recordData.userId : null;
    const safeArtifactId = typeof recordData.artifactId === "string" ? recordData.artifactId : null;
    const safeSessionId = typeof recordData.sessionIdentity === "string" ? recordData.sessionIdentity : null;

    try {
      await idempotencyRef.set({
        status: "REJECTED",
        reason: validationResult.reason,
        field: validationResult.field || null,
        details: validationResult.details || null,
        userId: safeUserId,
        artifactId: safeArtifactId,
        sessionIdentity: safeSessionId,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      }, { merge: true });
    } catch (e) {
      logger.error(`[PLAY_AGGREGATE] Failed to record durable rejection | PlayID=${playId}:`, e);
    }

    return {
      status: "REJECTED",
      reason: validationResult.reason,
      playId,
      diagnostic: validationResult.details,
    };
  }

  const { data } = validationResult;
  const { userId, artifactId, sessionIdentity } = data;

  const tombstoneRef = db.collection("deleted_users").doc(userId);
  const artifactRef = db.collection("artifacts").doc(artifactId);

  return await db.runTransaction(async (transaction) => {
    // -------------------------------------------------------------
    // READ PHASE: All reads executed concurrently via Promise.all
    // -------------------------------------------------------------
    const [idempotencyDoc, tombstoneDoc, artifactDoc] = await Promise.all([
      transaction.get(idempotencyRef),
      transaction.get(tombstoneRef),
      transaction.get(artifactRef),
    ]);

    if (options?.barrierAfterRead) {
      await options.barrierAfterRead(transaction);
    }

    // -------------------------------------------------------------
    // DECISION PHASE: No writes performed until all decisions complete
    // -------------------------------------------------------------

    // Decision 1: Idempotency check
    if (idempotencyDoc.exists) {
      const existingData = idempotencyDoc.data() || {};
      const existingStatus = existingData.status;

      if (existingStatus === "SUCCESS") {
        logger.info(`[PLAY_AGGREGATE] Already aggregated (SUCCESS) | PlayID=${playId}`);
        return {
          status: "ALREADY_PROCESSED",
          reason: "ALREADY_PROCESSED_SUCCESS",
          playId,
          artifactId,
          userId,
        };
      }
      if (existingStatus === "QUARANTINED") {
        logger.warn(`[PLAY_AGGREGATE] Already QUARANTINED | PlayID=${playId} | Preserving for operator review`);
        return {
          status: "QUARANTINED",
          reason: "ALREADY_QUARANTINED",
          playId,
          artifactId,
          userId,
        };
      }
      if (existingStatus === "REJECTED") {
        logger.info(`[PLAY_AGGREGATE] Already permanently REJECTED | PlayID=${playId}`);
        return {
          status: "REJECTED",
          reason: existingData.reason || "ALREADY_REJECTED",
          playId,
          artifactId,
          userId,
        };
      }

      // Fail-closed handling for any missing, null, malformed or unknown status
      const rawStatusDiagnostic = {
        rawStatus: existingStatus === undefined ? "missing" : existingStatus,
        rawStatusType: typeof existingStatus,
      };

      logger.error(
        `[PLAY_AGGREGATE] OPERATIONAL_ALERT: Unknown or malformed idempotency state | PlayID=${playId} | Status=${String(existingStatus)}`,
        {
          playId,
          artifactId,
          userId,
          rawStatusDiagnostic,
          existingData,
        }
      );

      // Record a durable terminal rejection reason without overwriting the existing record blindly.
      // Use merge: true to preserve the original diagnostic information sufficiently to investigate the malformed record.
      transaction.set(
        idempotencyRef,
        {
          status: "REJECTED",
          reason: "UNKNOWN_IDEMPOTENCY_STATE",
          originalMalformedState: {
            rawStatus: existingStatus === undefined ? "missing" : existingStatus,
            rawStatusType: typeof existingStatus,
            recordedAt: FieldValue.serverTimestamp(),
          },
          completedAt: FieldValue.serverTimestamp(),
          expiresAt,
          userId: existingData.userId ?? userId,
          artifactId: existingData.artifactId ?? artifactId,
          sessionIdentity: existingData.sessionIdentity ?? sessionIdentity,
        },
        { merge: true }
      );

      return {
        status: "REJECTED",
        reason: "UNKNOWN_IDEMPOTENCY_STATE",
        playId,
        artifactId,
        userId,
        diagnostic: rawStatusDiagnostic,
      };
    }

    // Decision 2: Stage E — Tombstone and Deletion Protection
    if (tombstoneDoc.exists) {
      const tombstoneState = tombstoneDoc.data()?.state;
      if (tombstoneState === "PENDING_DELETION" || tombstoneState === "PENDING" || tombstoneState === "DELETED") {
        logger.warn(`[PLAY_AGGREGATE] Rejected: User deleted or pending deletion | UID=${userId} | State=${tombstoneState}`);
        transaction.set(idempotencyRef, {
          status: "REJECTED",
          reason: `USER_${tombstoneState}`,
          userId,
          artifactId,
          sessionIdentity,
          completedAt: FieldValue.serverTimestamp(),
          expiresAt,
        });
        return {
          status: "REJECTED",
          reason: `USER_${tombstoneState}`,
          playId,
          artifactId,
          userId,
        };
      }

      // Malformed or unknown tombstone state: fail closed
      logger.error(`[PLAY_AGGREGATE] OPERATIONAL_ALERT: Malformed tombstone state | UID=${userId} | State=${tombstoneState}`);
      transaction.set(idempotencyRef, {
        status: "REJECTED",
        reason: "UNKNOWN_TOMBSTONE_STATE",
        userId,
        artifactId,
        sessionIdentity,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      });
      return {
        status: "REJECTED",
        reason: "UNKNOWN_TOMBSTONE_STATE",
        playId,
        artifactId,
        userId,
      };
    }

    // Decision 3: Artifact Existence & Eligibility
    if (!artifactDoc.exists) {
      logger.warn(`[PLAY_AGGREGATE] Rejected: Artifact not found | ArtifactID=${artifactId} | PlayID=${playId}`);
      transaction.set(idempotencyRef, {
        status: "REJECTED",
        reason: "ARTIFACT_NOT_FOUND",
        userId,
        artifactId,
        sessionIdentity,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      });
      return {
        status: "REJECTED",
        reason: "ARTIFACT_NOT_FOUND",
        playId,
        artifactId,
        userId,
      };
    }

    const artifactData = artifactDoc.data() || {};

    if (artifactData.status === "DELETED") {
      logger.warn(`[PLAY_AGGREGATE] Rejected: Artifact is DELETED | ArtifactID=${artifactId} | PlayID=${playId}`);
      transaction.set(idempotencyRef, {
        status: "REJECTED",
        reason: "ARTIFACT_DELETED",
        userId,
        artifactId,
        sessionIdentity,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      });
      return {
        status: "REJECTED",
        reason: "ARTIFACT_DELETED",
        playId,
        artifactId,
        userId,
      };
    }

    // Decision 4: Creator Self-Listening Exclusion
    if (artifactData.userId === userId) {
      logger.info(`[PLAY_AGGREGATE] Excluded: Creator self-listening | ArtifactID=${artifactId} | UID=${userId}`);
      transaction.set(idempotencyRef, {
        status: "REJECTED",
        reason: "CREATOR_SELF_PLAY",
        userId,
        artifactId,
        sessionIdentity,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      });
      return {
        status: "REJECTED",
        reason: "CREATOR_SELF_PLAY",
        playId,
        artifactId,
        userId,
      };
    }

    // Decision 5: Stage C — Counter Integrity
    const currentCounter = artifactData.qualifiedPlayCount;

    if (!isValidQualifiedCounter(currentCounter)) {
      const diagnostic = {
        rawValue: currentCounter === undefined ? "undefined" : currentCounter,
        valueType: typeof currentCounter,
        artifactId,
        playId,
      };
      logger.error(`[PLAY_AGGREGATE] OPERATIONAL_ERROR: Corrupted qualifiedPlayCount | ArtifactID=${artifactId}`, diagnostic);

      transaction.set(idempotencyRef, {
        status: "QUARANTINED",
        reason: "INVALID_QUALIFIED_COUNTER",
        diagnostic,
        userId,
        artifactId,
        sessionIdentity,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      });

      return {
        status: "QUARANTINED",
        reason: "INVALID_QUALIFIED_COUNTER",
        playId,
        artifactId,
        userId,
        diagnostic,
      };
    }

    if (isCounterExhausted(currentCounter)) {
      const diagnostic = {
        rawValue: currentCounter,
        maxSafeInteger: Number.MAX_SAFE_INTEGER,
        artifactId,
        playId,
      };
      logger.error(`[PLAY_AGGREGATE] OPERATIONAL_ERROR: Counter reached MAX_SAFE_INTEGER | ArtifactID=${artifactId}`, diagnostic);

      transaction.set(idempotencyRef, {
        status: "QUARANTINED",
        reason: "COUNTER_MAX_SAFE_INTEGER",
        diagnostic,
        userId,
        artifactId,
        sessionIdentity,
        completedAt: FieldValue.serverTimestamp(),
        expiresAt,
      });

      return {
        status: "QUARANTINED",
        reason: "COUNTER_MAX_SAFE_INTEGER",
        playId,
        artifactId,
        userId,
        diagnostic,
      };
    }

    // -------------------------------------------------------------
    // WRITE PHASE: Atomic Counter Increment and Idempotency Commit
    // -------------------------------------------------------------
    // Increment ONLY qualifiedPlayCount. Historical playCount is NEVER modified.
    transaction.update(artifactRef, {
      qualifiedPlayCount: FieldValue.increment(1),
    });

    transaction.set(idempotencyRef, {
      status: "SUCCESS",
      userId,
      artifactId,
      sessionIdentity,
      completedAt: FieldValue.serverTimestamp(),
      expiresAt,
    });

    logger.info(`[PLAY_AGGREGATE] qualifiedPlayCount incremented | ArtifactID=${artifactId} | PlayID=${playId}`);

    return {
      status: "SUCCESS",
      playId,
      artifactId,
      userId,
    };
  });
}

/**
 * Stage G — Controlled Administrative Quarantine Recovery
 *
 * Explicit operator process to recover a quarantined event after manual counter repair.
 * Fully revalidates the original artifact_plays event payload using the authoritative
 * validateArtifactPlayEvent() function both in preflight and inside the recovery transaction.
 */
export async function recoverQuarantinedPlayEvent(
  db: admin.firestore.Firestore,
  playId: string,
  options: {
    operatorUid: string;
    expectedRepairedCounter?: number;
    dryRun?: boolean;
    nowMs?: number;
    recoveryNotes?: string;
  }
): Promise<AggregationOutcome> {
  const nowMs = options.nowMs ?? Date.now();
  const idempotencyRef = db.collection("idempotency_keys").doc(playId);
  const playDocRef = db.collection("artifact_plays").doc(playId);

  // Preflight read phase outside transaction
  const [idempotencySnap, playSnap] = await Promise.all([
    idempotencyRef.get(),
    playDocRef.get(),
  ]);

  if (!idempotencySnap.exists) {
    throw new Error(`[RECOVERY_ABORT] Idempotency record for PlayID '${playId}' does not exist.`);
  }

  const idemData = idempotencySnap.data();
  if (idemData?.status !== "QUARANTINED") {
    throw new Error(
      `[RECOVERY_ABORT] PlayID '${playId}' is in status '${idemData?.status}'. Only QUARANTINED events can be recovered.`
    );
  }

  if (!playSnap.exists) {
    throw new Error(`[RECOVERY_ABORT] Artifact play document '${playId}' does not exist.`);
  }

  const preflightPlayData = playSnap.data();
  // Authoritative revalidation using current server time
  const preflightValidation = validateArtifactPlayEvent(playId, preflightPlayData, { nowMs });
  if (!preflightValidation.valid) {
    throw new Error(
      `[RECOVERY_ABORT] Quarantined event failed authoritative validation | Reason=${preflightValidation.reason} | Field=${preflightValidation.field ?? "none"}`
    );
  }

  const { userId, artifactId } = preflightValidation.data;

  if (options.dryRun) {
    return {
      status: "QUARANTINED",
      reason: "DRY_RUN_ELIGIBLE_FOR_RECOVERY",
      playId,
      artifactId,
      userId,
    };
  }

  const tombstoneRef = db.collection("deleted_users").doc(userId);
  const artifactRef = db.collection("artifacts").doc(artifactId);

  return await db.runTransaction(async (transaction) => {
    // Transactional read phase: read idempotency, play event, tombstone, and artifact concurrently
    const [currentIdemDoc, currentPlayDoc, currentTombstoneDoc, currentArtifactDoc] = await Promise.all([
      transaction.get(idempotencyRef),
      transaction.get(playDocRef),
      transaction.get(tombstoneRef),
      transaction.get(artifactRef),
    ]);

    if (!currentIdemDoc.exists || currentIdemDoc.data()?.status !== "QUARANTINED") {
      throw new Error("[RECOVERY_ABORT] Concurrently modified: Idempotency status is no longer QUARANTINED.");
    }

    if (!currentPlayDoc.exists) {
      throw new Error(`[RECOVERY_ABORT] Artifact play document "${playId}" does not exist in transaction.`);
    }

    // Authoritative revalidation inside the transaction against current transactional state
    const currentPlayData = currentPlayDoc.data();
    const txValidation = validateArtifactPlayEvent(playId, currentPlayData, { nowMs });
    if (!txValidation.valid) {
      throw new Error(
        `[RECOVERY_ABORT] Quarantined event failed transactional validation | Reason=${txValidation.reason} | Field=${txValidation.field ?? "none"}`
      );
    }

    // Verify identifiers match preflight to prevent payload substitution
    if (txValidation.data.userId !== userId || txValidation.data.artifactId !== artifactId) {
      throw new Error(
        "[RECOVERY_ABORT] Event payload was modified between preflight and transaction."
      );
    }

    if (currentTombstoneDoc.exists) {
      const tombstoneState = currentTombstoneDoc.data()?.state;
      throw new Error(`[RECOVERY_ABORT] User '${userId}' is deleted or pending deletion (State=${tombstoneState}).`);
    }

    if (!currentArtifactDoc.exists || currentArtifactDoc.data()?.status === "DELETED") {
      throw new Error(`[RECOVERY_ABORT] Artifact '${artifactId}' does not exist or is DELETED.`);
    }

    const currentArtifactData = currentArtifactDoc.data() || {};
    if (currentArtifactData.userId === userId) {
      throw new Error("[RECOVERY_ABORT] Creator self-listening event cannot be recovered as qualified play.");
    }

    const currentCounter = currentArtifactData.qualifiedPlayCount;
    if (!isValidQualifiedCounter(currentCounter) || isCounterExhausted(currentCounter)) {
      throw new Error(
        `[RECOVERY_ABORT] Artifact counter is still invalid or exhausted (value: ${currentCounter}). Repair must be verified before recovery.`
      );
    }

    if (
      options.expectedRepairedCounter !== undefined &&
      currentCounter !== options.expectedRepairedCounter
    ) {
      throw new Error(
        `[RECOVERY_ABORT] Artifact counter (${currentCounter}) does not match expected repaired value (${options.expectedRepairedCounter}).`
      );
    }

    // Atomically increment counter and transition idempotency record
    transaction.update(artifactRef, {
      qualifiedPlayCount: FieldValue.increment(1),
    });

    transaction.update(idempotencyRef, {
      status: "SUCCESS",
      recoveredBy: options.operatorUid,
      recoveredAt: FieldValue.serverTimestamp(),
      previousStatus: "QUARANTINED",
      recoveryNotes: options.recoveryNotes ?? "Operator authorized quarantine recovery",
    });

    logger.info(
      `[OPERATOR_RECOVERY] Successfully recovered PlayID=${playId} | ArtifactID=${artifactId} | Operator=${options.operatorUid}`
    );

    return {
      status: "SUCCESS",
      reason: "RECOVERED_FROM_QUARANTINE",
      playId,
      artifactId,
      userId,
    };
  });
}
