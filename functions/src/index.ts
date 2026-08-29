import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import {FieldValue} from "firebase-admin/firestore";
import {withIdempotency} from "./util/idempotency";
import {logger} from "./util/logger";
import {validateCoverage} from "./util/validation/coverage";
import {getPolicy} from "./util/validation/policy";
import {VALIDATION_VERSION, UnlockReason} from "./util/validation/constants";
import {ModerationConfig} from "./util/moderation/config";

if (!admin.apps.length) {
  admin.initializeApp();
}

/**
 * Authoritatively deletes all documents returned by a query in batches of 500.
 * Ensures Firestore limits are respected while draining large collections.
 *
 * @param db The Firestore instance.
 * @param query The query identifying documents to delete.
 * @param label A diagnostic label for logging.
 * @returns The total number of deleted documents.
 */
async function deleteQueryBatch(
  db: admin.firestore.Firestore,
  query: admin.firestore.Query,
  label: string
): Promise<number> {
  let totalDeleted = 0;
  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      // 1. Fetch at most 500 documents (BulkWriter will manage internal batches)
      const querySnapshot = await query.limit(500).get();

      // 2. Return immediately when no documents remain
      if (querySnapshot.size === 0) {
        if (totalDeleted > 0) {
          logger.info(`[BATCH_DELETE] ${label} | FINISHED | Total=${totalDeleted}`);
        } else {
          logger.info(`[BATCH_DELETE] ${label} | NONE`);
        }
        break;
      }

      // 3. Delete the fetched documents using BulkWriter for improved efficiency
      const bulkWriter = db.bulkWriter();
      querySnapshot.docs.forEach((doc) => bulkWriter.delete(doc.ref));

      // 4. Wait for the operations to complete
      await bulkWriter.close();

      totalDeleted += querySnapshot.size;
      logger.info(`[BATCH_DELETE] ${label} | DELETED Batch=${querySnapshot.size} | Cumulative=${totalDeleted}`);

      // 5. Repeat until the collection is empty
      // If we got fewer than 500, we know we are done without an extra round-trip
      if (querySnapshot.size < 500) {
        logger.info(`[BATCH_DELETE] ${label} | FINISHED | Total=${totalDeleted}`);
        break;
      }
    }
    return totalDeleted;
  } catch (e) {
    logger.error(`[BATCH_DELETE] ${label} | ERROR:`, e);
    throw e; // Rethrow to trigger Function retry
  }
}

/**
 * Authoritatively updates all documents returned by a query in batches.
 * Uses BulkWriter for high-throughput updates.
 */
async function updateQueryBatch(
  db: admin.firestore.Firestore,
  query: admin.firestore.Query,
  updates: any,
  label: string
): Promise<number> {
  let totalUpdated = 0;
  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const querySnapshot = await query.limit(500).get();

      if (querySnapshot.size === 0) break;

      const bulkWriter = db.bulkWriter();
      querySnapshot.docs.forEach((doc) => {
        bulkWriter.update(doc.ref, updates);
      });
      await bulkWriter.close();

      totalUpdated += querySnapshot.size;
      logger.info(`[BATCH_UPDATE] ${label} | UPDATED Batch=${querySnapshot.size} | Cumulative=${totalUpdated}`);

      if (querySnapshot.size < 500) break;
    }
    return totalUpdated;
  } catch (e) {
    logger.error(`[BATCH_UPDATE] ${label} | ERROR:`, e);
    throw e;
  }
}

/**
 * Robust cascading cleanup triggered when an artifact's status changes to DELETED.
 * Handles Storage files, reactions, aggregates, metadata, and final document deletion.
 * Designed for idempotency and high reliability.
 */
export const onArtifactCleanupTrigger = functions
  .runWith({
    timeoutSeconds: 540,
    memory: "512MB",
  })
  .firestore.document("artifacts/{artifactId}")
  .onUpdate(async (change, context) => {
    const newData = change.after.data();
    const oldData = change.before.data();

    if (!newData || !oldData) {
      return null;
    }

    const artifactId = context.params.artifactId;
    const db = admin.firestore();
    const bucket = admin.storage().bucket();
    const startTime = Date.now();

    // Only trigger if status transitioned to DELETED
    if (newData.status !== "DELETED" || oldData.status === "DELETED") {
      return null;
    }

    // FINAL SAFETY GUARD: Prevent deletion if Legal Hold is active.
    // We fetch the LATEST state to avoid race conditions with Admin hold placement.
    const latestDocSnapshot = await db.collection("artifacts").doc(artifactId).get();
    const latestData = latestDocSnapshot.exists ? latestDocSnapshot.data() : null;

    if (latestData?.moderation?.legalHold === true) {
      logger.info(`[CLEANUP] Preservation Active | ArtifactID=${artifactId} | Skipping deletion (Fresh Read).`);
      return null;
    }

    logger.info(`[CLEANUP] START | ArtifactID=${artifactId} | EventId=${context.eventId}`);

    // REQUIREMENT 1: Captured-State Deletion (Read all metadata from latest doc or trigger snapshot)
    const effectiveData = latestData || newData;
    const audioUrl = effectiveData.audioUrl;
    const transcriptUrl = effectiveData.transcriptUrl;
    const userId = effectiveData.userId;

    try {
      // 1. Storage Cleanup: Audio
      // Primary: Delete via URL in document
      let audioDeleted = false;
      if (audioUrl && audioUrl.includes("firebasestorage") && audioUrl.includes("/o/")) {
        try {
          const parts = audioUrl.split("/o/");
          if (parts.length > 1) {
            const decodedPath = decodeURIComponent(parts[1].split("?")[0]);
            await admin.storage().bucket().file(decodedPath).delete();
            logger.info(`[CLEANUP] Audio | DELETED | Path=${decodedPath}`);
            audioDeleted = true;
          }
        } catch (e: any) {
          if (e.code === 404) {
            logger.warn(`[CLEANUP] Audio | 404 on URL | Path=${audioUrl}`);
            // Do NOT set audioDeleted=true, let safety net try predictable path
          } else {
            logger.error("[CLEANUP] Audio | ERROR:", e);
          }
        }
      }

      if (!audioDeleted && userId) {
        // Safety Net: Predictable path deletion for incomplete/abandoned artifacts
        const predictablePath = `artifacts/${userId}_${artifactId}.m4a`;
        try {
          await bucket.file(predictablePath).delete();
          logger.info(`[CLEANUP] Audio | SafetyNet DELETED | Path=${predictablePath}`);
          audioDeleted = true;
        } catch (e: any) {
          if (e.code === 404) {
            logger.info(`[CLEANUP] Audio | SafetyNet 404 | Path=${predictablePath}`);
          } else {
            logger.error(`[CLEANUP] Audio | SafetyNet Error | Path=${predictablePath}:`, e);
          }
        }
      }

      // 2. Storage Cleanup: Transcript
      let transcriptDeleted = false;
      if (transcriptUrl && transcriptUrl.includes("firebasestorage") && transcriptUrl.includes("/o/")) {
        try {
          const parts = transcriptUrl.split("/o/");
          if (parts.length > 1) {
            const decodedPath = decodeURIComponent(parts[1].split("?")[0]);
            await admin.storage().bucket().file(decodedPath).delete();
            logger.info(`[CLEANUP] Transcript | DELETED | Path=${decodedPath}`);
            transcriptDeleted = true;
          }
        } catch (e: any) {
          if (e.code === 404) {
            logger.warn(`[CLEANUP] Transcript | 404 on URL | Path=${transcriptUrl}`);
          } else {
            logger.error("[CLEANUP] Transcript | ERROR:", e);
          }
        }
      }

      if (!transcriptDeleted && userId) {
        // Safety Net for Transcripts
        const predictablePath = `transcripts/${userId}_${artifactId}.json`;
        try {
          await bucket.file(predictablePath).delete();
          logger.info(`[CLEANUP] Transcript | SafetyNet DELETED | Path=${predictablePath}`);
          transcriptDeleted = true;
        } catch (e: any) {
          // 404 is expected
        }
      }

      // 3. Comments Cleanup (Subcollection)
      try {
        await db.recursiveDelete(change.after.ref.collection("comments"));
        logger.info("[CLEANUP] Comments | DELETED");
      } catch (e) {
        logger.error("[CLEANUP] Comments | ERROR:", e);
        throw e;
      }

      // 4. Reactions (Subcollection)
      try {
        await db.recursiveDelete(change.after.ref.collection("reactions"));
        logger.info("[CLEANUP] Sub-Reactions | DELETED");
      } catch (e) {
        logger.error("[CLEANUP] Sub-Reactions | ERROR:", e);
        throw e;
      }

      // 5. Top-level Reactions (artifact_reactions)
      await deleteQueryBatch(
        db,
        db.collection("artifact_reactions").where("artifactId", "==", artifactId),
        "Global Reactions"
      );

      // 6. Reaction Counts (Aggregate)
      try {
        await db.collection("artifact_reaction_counts").doc(artifactId).delete();
        logger.info("[CLEANUP] Aggregates | DELETED");
      } catch (e) {
        logger.error("[CLEANUP] Aggregates | ERROR:", e);
        throw e;
      }

      // 7. Notifications
      await deleteQueryBatch(
        db,
        db.collection("notifications").where("artifactId", "==", artifactId),
        "Notifications"
      );

      // 8. Engagement Records (Collection Group)
      await deleteQueryBatch(
        db,
        db.collectionGroup("engagement").where("artifactId", "==", artifactId),
        "Engagement Records"
      );

      // 9. Ownership Record
      if (userId) {
        try {
          await db.collection("users").doc(userId)
            .collection("private").doc("published_artifacts")
            .collection("artifacts").doc(artifactId)
            .delete();
          logger.info("[CLEANUP] Ownership Record | DELETED");
        } catch (e) {
          logger.error("[CLEANUP] Ownership Record | ERROR:", e);
          throw e;
        }
      }

      // 10. Private Feedback
      await deleteQueryBatch(
        db,
        db.collection("feedback_private").where("artifactId", "==", artifactId),
        "Private Feedback"
      );

      // 11. Artifact Plays (Aggregation Source)
      await deleteQueryBatch(
        db,
        db.collection("artifact_plays").where("artifactId", "==", artifactId),
        "Artifact Plays"
      );

      // 12. FINAL: Delete the Artifact document itself
      await change.after.ref.delete();
      logger.info("[CLEANUP] Artifact Document | DELETED");

      const totalDuration = Date.now() - startTime;
      logger.info(`[CLEANUP] FINISH | ArtifactID=${artifactId} | TotalDuration=${totalDuration}ms`);

      return null;
    } catch (error) {
      logger.error(`[CLEANUP] FATAL ERROR | ArtifactID=${artifactId}:`, error);
      throw error; // Trigger function retry
    }
  });

/**
 * Updates global reaction aggregates when a new reaction is created.
 */
export const onReactionCreated = functions.firestore
  .document("artifact_reactions/{reactionId}")
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data) return null;

    const artifactId = data.artifactId;
    const typeId = data.type;
    const db = admin.firestore();
    const eventId = context.eventId;

    return withIdempotency(`react_inc_${eventId}`, async () => {
      logger.info(`[REACTION] Incrementing counts | ArtifactID=${artifactId} | Type=${typeId}`);

      const batch = db.batch();

      // 1. Update Aggregate Document
      const aggregateRef = db.collection("artifact_reaction_counts").doc(artifactId);
      batch.set(
        aggregateRef,
        {
          totalCount: FieldValue.increment(1),
          [`breakdown.${typeId}`]: FieldValue.increment(1),
          lastUpdated: FieldValue.serverTimestamp(),
        },
        {merge: true}
      );

      // 2. Update Main Artifact Metadata
      const artifactRef = db.collection("artifacts").doc(artifactId);
      batch.update(artifactRef, {
        reactionCount: FieldValue.increment(1),
      });

      await batch.commit();
      logger.info(`[REACTION] Successfully updated counts | ArtifactID=${artifactId}`);
    });
  });

/**
 * Updates global reaction aggregates when a reaction is deleted.
 */
export const onReactionDeleted = functions.firestore
  .document("artifact_reactions/{reactionId}")
  .onDelete(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data) return null;

    const artifactId = data.artifactId;
    const typeId = data.type;
    const db = admin.firestore();
    const eventId = context.eventId;

    return withIdempotency(`react_dec_${eventId}`, async () => {
      logger.info(`[REACTION] Decrementing counts | ArtifactID=${artifactId} | Type=${typeId}`);

      const batch = db.batch();

      // 1. Update Aggregate Document
      const aggregateRef = db.collection("artifact_reaction_counts").doc(artifactId);
      batch.set(
        aggregateRef,
        {
          totalCount: FieldValue.increment(-1),
          [`breakdown.${typeId}`]: FieldValue.increment(-1),
          lastUpdated: FieldValue.serverTimestamp(),
        },
        {merge: true}
      );

      // 2. Update Main Artifact Metadata
      const artifactRef = db.collection("artifacts").doc(artifactId);
      batch.update(artifactRef, {
        reactionCount: FieldValue.increment(-1),
      });

      await batch.commit();
      logger.info(`[REACTION] Successfully decremented counts | ArtifactID=${artifactId}`);
    });
  });

/**
 * Authoritatively handles follow/resonance intents.
 */
export const onFollowIntentCreated = functions.firestore
  .document("users/{uid}/private/intents/follow/{targetId}")
  .onCreate(async (snapshot, context) => {
    const uid = context.params.uid;
    const targetId = context.params.targetId;
    const data = snapshot.data();

    if (!data || data.action !== "FOLLOW") return null;

    const idempotencyKey = `follow_${uid}_${targetId}_${data.timestamp?.seconds || "initial"}`;

    return withIdempotency(idempotencyKey, async () => {
      const db = admin.firestore();
      const currentUserRef = db.collection("users").doc(uid);
      const targetUserRef = db.collection("users").doc(targetId);

      const resonanceOutRef = currentUserRef.collection("resonance_out").doc(targetId);
      const resonanceInRef = targetUserRef.collection("resonance_in").doc(uid);

      await db.runTransaction(async (transaction) => {
        const outDoc = await transaction.get(resonanceOutRef);
        if (outDoc.exists) {
          logger.info(`Follow: ${uid} is already resonating with ${targetId}`);
          return;
        }

        const timestamp = FieldValue.serverTimestamp();

        // 1. Create Markers
        transaction.set(resonanceOutRef, {createdAt: timestamp});
        transaction.set(resonanceInRef, {createdAt: timestamp});

        // 2. Update Counters
        transaction.update(currentUserRef, {
          resonanceOutCount: FieldValue.increment(1),
          followingCount: FieldValue.increment(1),
        });
        transaction.update(targetUserRef, {
          resonanceInCount: FieldValue.increment(1),
          followersCount: FieldValue.increment(1),
        });
      });

      // 3. Authoritative Preference Check
      const targetSettingsDoc = await db.collection("users").doc(targetId)
        .collection("private").doc("settings")
        .get();

      // Silent Ignore Boundary Check (Phase 6.3.1)
      const ignoreDoc = await db.collection("users").doc(targetId)
        .collection("private").doc("ignored_users")
        .collection("users").doc(uid)
        .get();

      if (ignoreDoc.exists) {
        logger.info(`[FOLLOW_NOTIF] Suppressed due to ignore | Recipient=${targetId} | Sender=${uid}`);
        return;
      }

      if (targetSettingsDoc.data()?.notificationsEnabled !== false) {
        // Create Notification
        await admin.firestore().collection("notifications").add({
          userId: targetId,
          followerId: uid,
          message: "PRESENCE_RESONATED",
          type: "FOLLOW",
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          isRead: false,
        });
      }

      logger.interaction("FOLLOW_SUCCESS", {userId: uid, artifactId: targetId}, "SUCCESS");
    });
  });

/**
 * Handles unfollow intent.
 */
export const onFollowIntentDeleted = functions.firestore
  .document("users/{uid}/private/intents/follow/{targetId}")
  .onDelete(async (snapshot, context) => {
    const uid = context.params.uid;
    const targetId = context.params.targetId;

    const db = admin.firestore();
    const currentUserRef = db.collection("users").doc(uid);
    const targetUserRef = db.collection("users").doc(targetId);

    const resonanceOutRef = currentUserRef.collection("resonance_out").doc(targetId);
    const resonanceInRef = targetUserRef.collection("resonance_in").doc(uid);

    await db.runTransaction(async (transaction) => {
      const outDoc = await transaction.get(resonanceOutRef);
      if (!outDoc.exists) return;

      // 1. Delete Markers
      transaction.delete(resonanceOutRef);
      transaction.delete(resonanceInRef);

      // 2. Decrement Counters
      transaction.update(currentUserRef, {
        resonanceOutCount: FieldValue.increment(-1),
        followingCount: FieldValue.increment(-1),
      });
      transaction.update(targetUserRef, {
        resonanceInCount: FieldValue.increment(-1),
        followersCount: FieldValue.increment(-1),
      });
    });

    logger.interaction("UNFOLLOW_SUCCESS", {userId: uid, artifactId: targetId}, "SUCCESS");
    return null;
  });

/**
 * Triggers on reaction intent to authoritatively create notification and markers.
 */
export const onReactionIntentCreated = functions.firestore
  .document("users/{uid}/private/intents/reactions/{artifactId}")
  .onCreate(async (snapshot, context) => {
    const uid = context.params.uid;
    const artifactId = context.params.artifactId;
    const data = snapshot.data();

    if (!data || data.action !== "ADD") return null;

    const idempotencyKey = `react_${uid}_${artifactId}_${data.timestamp?.seconds || "initial"}`;

    return withIdempotency(idempotencyKey, async () => {
      const db = admin.firestore();

      // 1. Get Artifact Owner and verify visibility
      const artifactDoc = await db.collection("artifacts").doc(artifactId).get();
      if (!artifactDoc.exists) return;
      const artifactData = artifactDoc.data()!;

      // Privacy Boundary: Block notifications for private artifacts if not by owner
      if (!artifactData.isPublic && artifactData.userId !== uid) {
        logger.info(`[REACTION_NOTIF] Suppressed for private artifact | ArtifactID=${artifactId}`);
        return;
      }

      const ownerId = artifactData.userId;

      const reactionId = `${artifactId}_${uid}`;
      const globalRef = db.collection("artifact_reactions").doc(reactionId);

      await db.runTransaction(async (transaction) => {
        const globalDoc = await transaction.get(globalRef);
        if (globalDoc.exists) return;

        // 2. Create Global Reaction Marker (Zero-Trust)
        transaction.set(globalRef, {
          artifactId: artifactId,
          userId: uid,
          artifactOwnerId: ownerId,
          type: data.type,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });

      // 3. Create Notification for Owner (with Preference Check)
      if (ownerId && ownerId !== uid) {
        const ownerSettingsDoc = await db.collection("users").doc(ownerId)
          .collection("private").doc("settings")
          .get();

        // Silent Ignore Boundary Check (Phase 6.3.1)
        const ignoreDoc = await db.collection("users").doc(ownerId)
          .collection("private").doc("ignored_users")
          .collection("users").doc(uid)
          .get();

        if (ignoreDoc.exists) {
          logger.info(`[REACTION_NOTIF] Suppressed due to ignore | Recipient=${ownerId} | Sender=${uid}`);
          return;
        }

        if (ownerSettingsDoc.data()?.notificationsEnabled !== false) {
          await db.collection("notifications").add({
            userId: ownerId,
            message: `RESONANCE|${data.type}`,
            artifactId: artifactId,
            type: "RESONANCE",
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            isRead: false,
          });
        }
      }

      logger.interaction("REACTION_SUCCESS", {userId: uid, artifactId: artifactId}, "SUCCESS");
    });
  });

/**
 * Triggers on reaction intent deletion.
 */
export const onReactionIntentDeleted = functions.firestore
  .document("users/{uid}/private/intents/reactions/{artifactId}")
  .onDelete(async (snapshot, context) => {
    const uid = context.params.uid;
    const artifactId = context.params.artifactId;

    const db = admin.firestore();
    const reactionId = `${artifactId}_${uid}`;

    await db.collection("artifact_reactions").doc(reactionId).delete();

    logger.interaction("REACTION_REMOVED", {userId: uid, artifactId: artifactId}, "SUCCESS");
    return null;
  });

/**
 * Authoritatively handles artifact creation notifications.
 */
export const onArtifactCreated = functions.firestore
  .document("artifacts/{artifactId}")
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data || data.status !== "ACTIVE") return null;

    const artifactId = context.params.artifactId;
    const userId = data.userId;
    const title = data.title || "Unknown Artifact";

    const idempotencyKey = `art_notif_${artifactId}`;

    return withIdempotency(idempotencyKey, async () => {
      const db = admin.firestore();

      await db.collection("notifications").add({
        userId: userId,
        message: `NEW_ARTIFACT|${title}`,
        artifactId: artifactId,
        type: "SYSTEM",
        createdAt: FieldValue.serverTimestamp(),
        isRead: false,
      });

      logger.info(`Creation notification sent for artifact ${artifactId}`);
    });
  });

/**
 * Authoritatively updates identity fields in batches, ensuring version safety.
 *
 * @param db The Firestore instance.
 * @param query The query identifying candidate documents.
 * @param updateData The identity fields to update.
 * @param newVersion The target identity version.
 * @param label A diagnostic label for logging.
 */
async function updateIdentitySafe(
  db: admin.firestore.Firestore,
  query: admin.firestore.Query,
  updateData: any,
  newVersion: number,
  label: string
): Promise<number> {
  let totalProcessed = 0;
  let totalUpdated = 0;
  let lastDoc = null;

  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      let pagedQuery = query.orderBy("__name__").limit(500);
      if (lastDoc) {
        pagedQuery = pagedQuery.startAfter(lastDoc);
      }

      const querySnapshot = await pagedQuery.get();
      if (querySnapshot.size === 0) break;

      const batch = db.batch();
      let updatedInBatch = 0;

      querySnapshot.docs.forEach((doc) => {
        const storedVersion = doc.data().identityVersion || 0;
        // Invariant: Only update if the incoming version is newer.
        // This handles concurrent resets and prevents stale overwrites.
        if (newVersion > storedVersion) {
          batch.update(doc.ref, {
            ...updateData,
            identityVersion: newVersion,
          });
          updatedInBatch++;
        }
        lastDoc = doc;
      });

      if (updatedInBatch > 0) {
        await batch.commit();
        totalUpdated += updatedInBatch;
      }

      totalProcessed += querySnapshot.size;
      logger.info(`[IDENTITY_BATCH] ${label} | Processed=${querySnapshot.size} | Updated=${updatedInBatch} | Cumulative=${totalUpdated}`);

      if (querySnapshot.size < 500) break;
    }
    return totalUpdated;
  } catch (e) {
    logger.error(`[IDENTITY_BATCH] ${label} | ERROR:`, e);
    throw e;
  }
}

/**
 * Authoritative identity propagation triggered by identityResetVersion update.
 * Updates all historical Artifacts and Comments with the new anonymous identity.
 * Hardened with version safety and increased timeout for large historical sets.
 */
export const onUserIdentityReset = functions
  .runWith({
    timeoutSeconds: 540,
    memory: "512MB",
  })
  .firestore.document("users/{uid}")
  .onUpdate(async (change, context) => {
    const newData = change.after.data();
    const oldData = change.before.data();

    if (!newData || !oldData) return null;

    const newVersion = newData.identityMetadata?.identityResetVersion || 0;
    const oldVersion = oldData.identityMetadata?.identityResetVersion || 0;

    // Trigger only on monotonic version increase
    if (newVersion <= oldVersion) return null;

    const uid = context.params.uid;
    const db = admin.firestore();

    logger.info(`[IDENTITY_PROPAGATION] START | UID=${uid} | Version=${newVersion}`);

    // 1. Prepare AuthorSnapshot Update (Nested field notation for deep merge)
    const authorUpdate = {
      "author.name": newData.anonymousName || "quiet presence",
      "author.anonymousId": newData.anonymousId || "",
      "author.sigil": newData.anonymousSigil || "",
      "author.sigilSeed": newData.sigilSeed || "",
      "author.sigilColor": newData.sigilColor || "#FFD700",
      "author.sigilConfig": newData.sigilConfig || {},
    };

    try {
      // 2. Propagate to Artifacts (Version-Safe)
      const artifactsQuery = db.collection("artifacts").where("userId", "==", uid);
      await updateIdentitySafe(db, artifactsQuery, authorUpdate, newVersion, "User Artifacts");

      // 3. Propagate to Comments (Collection Group) (Version-Safe)
      const commentsQuery = db.collectionGroup("comments").where("creatorId", "==", uid);
      await updateIdentitySafe(db, commentsQuery, authorUpdate, newVersion, "User Comments");

      // 3.5 Optional Relationship Severing (Phase 6.4 Clean Break)
      const shouldSever = newData.identityMetadata?.severRelationships === true;
      if (shouldSever) {
        logger.info(`[IDENTITY_PROPAGATION] SEVERING RELATIONSHIPS | UID=${uid}`);
        await scaleResonanceCleanup(db, uid, "out");
        await scaleResonanceCleanup(db, uid, "in");
      }

      // 4. Finalize: Update lastCompletedIdentityVersion (Atomic Transaction)
      await db.runTransaction(async (transaction) => {
        const userRef = db.collection("users").doc(uid);
        const userDoc = await transaction.get(userRef);
        if (!userDoc.exists) return;

        const currentCompleted = userDoc.data()?.identityMetadata?.lastCompletedIdentityVersion || 0;

        const finalUpdate: any = {
          "identityMetadata.lastCompletedIdentityVersion": Math.max(newVersion, currentCompleted),
          "identityMetadata.resetCompletedAt": FieldValue.serverTimestamp(),
          // Purge the trigger flag
          "identityMetadata.severRelationships": FieldValue.delete(),
        };

        // If relationships were severed, reset counters to 0 to prevent ghost counts
        if (shouldSever) {
          finalUpdate.resonanceInCount = 0;
          finalUpdate.followersCount = 0;
          finalUpdate.resonanceOutCount = 0;
          finalUpdate.followingCount = 0;
        }

        transaction.update(userRef, finalUpdate);
      });

      logger.info(`[IDENTITY_PROPAGATION] SUCCESS | UID=${uid} | Version=${newVersion}`);
      return null;
    } catch (error) {
      logger.error(`[IDENTITY_PROPAGATION] FATAL ERROR | UID=${uid} | Version=${newVersion}:`, error);
      throw error; // Trigger Function retry
    }
  });

/**
 * Authoritatively handles permanent account cleanup when a user is deleted.
 * Hardened for idempotency and scalability with increased timeout and memory.
 */
export const onUserDeleted = functions
  .runWith({
    timeoutSeconds: 540,
    memory: "1GB",
  })
  .auth.user()
  .onDelete(async (user) => {
    const uid = user.uid;
    const db = admin.firestore();
    const bucket = admin.storage().bucket();
    const startTime = Date.now();

    logger.info(`[DELETE USER] START | UID=${uid}`);

    try {
      // 0. Storage Cleanup: Backups (Authoritative prefix purge)
      try {
        const [files] = await bucket.getFiles({ prefix: `backups/${uid}/` });
        if (files.length > 0) {
          for (const file of files) {
            await file.delete();
          }
          logger.info(`[DELETE USER] Storage | Backups Purged | Count=${files.length} | UID=${uid}`);
        }
      } catch (e) {
        logger.error(`[DELETE USER] Storage | Backups Error | UID=${uid}:`, e);
      }

      // 1. Cleanup Artifacts (SCALABLE: Paged cleanup marking)
      let artifactsProcessed = 0;
      while (true) {
        const artifactsQuery = db.collection("artifacts").where("userId", "==", uid).limit(500);
        const snapshot = await artifactsQuery.get();
        if (snapshot.empty) break;

        const bulkWriter = db.bulkWriter();
        snapshot.docs.forEach((doc) => {
          const data = doc.data();
          if (data.status !== "DELETED") {
            if (data.moderation?.legalHold === true) {
              logger.info(`[DELETE USER] Artifact Preserved (Legal Hold) | ArtifactID=${doc.id}`);
              return;
            }
            bulkWriter.update(doc.ref, {
              status: "DELETED",
              isPublic: false,
              deletedAt: FieldValue.serverTimestamp(),
            });
          }
        });
        await bulkWriter.close();
        artifactsProcessed += snapshot.size;
        logger.info(`[DELETE USER] Artifacts | Marked Batch=${snapshot.size} | Total=${artifactsProcessed}`);
        if (snapshot.size < 500) break;
      }

      // 2. Cleanup Notifications
      await deleteQueryBatch(
        db,
        db.collection("notifications").where("userId", "==", uid),
        "User Notifications"
      );

      // 3. Cleanup Resonances (SCALABLE: Paged parallel transactions)
      await scaleResonanceCleanup(db, uid, "out");
      await scaleResonanceCleanup(db, uid, "in");

      // 4. Cleanup Username (Mapping removal)
      try {
        const userDoc = await db.collection("users").doc(uid).get();
        const username = userDoc.data()?.anonymousName;
        if (username) {
          await db.collection("usernames").doc(username.toLowerCase().trim()).delete();
        }
        await deleteQueryBatch(db, db.collection("usernames").where("uid", "==", uid), "Username Safety Net");
      } catch (e) {
        logger.error("[DELETE USER] Stage=Username | ERROR:", e);
      }

      // 5. Cleanup Listening Sessions
      await deleteQueryBatch(
        db,
        db.collection("listening_sessions").where("userId", "==", uid),
        "User Listening Sessions"
      );

      // 5.5 Anonymize Comments (SCALABLE: Paged update)
      await updateQueryBatch(
        db,
        db.collectionGroup("comments").where("creatorId", "==", uid),
        { creatorId: "" },
        "Comments Anonymization"
      );

      // 5.6 Cleanup Authored Reports (SCALABLE: Paged delete)
      await deleteQueryBatch(
        db,
        db.collection("reports").where("reporterId", "==", uid),
        "Authored Reports"
      );

      // 6. Root Collection Cleanup (User-agnostic but UID-scoped data)
      const globalCollections = [
        { coll: "reactions_global", field: "userId", label: "Global Reactions" },
        { coll: "artifact_reactions", field: "userId", label: "Artifact Reactions" },
        { coll: "artifact_plays", field: "userId", label: "Artifact Plays" },
        { coll: "feedback_private", field: "userId", label: "Private Feedback" }
      ];

      for (const entry of globalCollections) {
        await deleteQueryBatch(
          db,
          db.collection(entry.coll).where(entry.field, "==", uid),
          entry.label
        );
      }

      // 7. FINAL: Recursive User Tree Destruction
      const userRef = db.collection("users").doc(uid);
      await db.recursiveDelete(userRef);

      const totalDuration = Date.now() - startTime;
      logger.info(`[DELETE USER] FINISH | UID=${uid} | Duration=${totalDuration}ms`);

      return null;
    } catch (error) {
      logger.error(`[DELETE USER] FATAL ERROR | UID=${uid}:`, error);
      throw error;
    }
  });

/**
 * Scales resonance cleanup by processing in pages and using parallel transactions.
 */
async function scaleResonanceCleanup(
  db: admin.firestore.Firestore,
  uid: string,
  type: "in" | "out"
): Promise<number> {
  const collectionName = type === "in" ? "resonance_in" : "resonance_out";
  const batchSize = 100;
  let totalProcessed = 0;

  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const snapshot = await db.collection("users").doc(uid).collection(collectionName).limit(batchSize).get();
      if (snapshot.empty) break;

      const promises = snapshot.docs.map(async (doc) => {
        const otherUserId = doc.id;
        return db.runTransaction(async (transaction) => {
          const markerRef = doc.ref;
          const otherUserRef = db.collection("users").doc(otherUserId);

          let reciprocalRef;
          let updateData: any = {};

          if (type === "out") {
            reciprocalRef = otherUserRef.collection("resonance_in").doc(uid);
            updateData = {
              resonanceInCount: FieldValue.increment(-1),
              followersCount: FieldValue.increment(-1)
            };
          } else {
            reciprocalRef = otherUserRef.collection("resonance_out").doc(uid);
            updateData = {
              resonanceOutCount: FieldValue.increment(-1),
              followingCount: FieldValue.increment(-1)
            };
          }

          const markerDoc = await transaction.get(markerRef);
          if (!markerDoc.exists) return;

          transaction.delete(markerRef);
          transaction.delete(reciprocalRef);
          transaction.update(otherUserRef, updateData);
        });
      });

      await Promise.all(promises);
      totalProcessed += snapshot.size;
      logger.info(`[DELETE USER] Resonance | type=${type} | Batch=${snapshot.size} | Total=${totalProcessed}`);

      if (snapshot.size < batchSize) break;
    }
  } catch (e) {
    logger.error(`[DELETE USER] Resonance Cleanup Error | type=${type}:`, e);
    throw e;
  }
  return totalProcessed;
}


/**
 * Authoritative backend validator for the "Listen Before You Respond" feature.
 * Triggers when a user's engagement record is updated.
 */
export const onEngagementUpdated = functions.firestore
  .document("users/{uid}/engagement/{artifactId}")
  .onWrite(async (change, context) => {
    const after = change.after.data();
    if (!after) return null; // Deletion handled by onUserDeleted or onArtifactCleanup

    // 1. Loop Prevention & Idempotency
    if (after.isCommentUnlocked === true) {
      return null;
    }

    const uid = context.params.uid;
    const artifactId = context.params.artifactId;
    const db = admin.firestore();

    try {
      // 2. Load Authoritative Artifact Metadata
      const artifactDoc = await db.collection("artifacts").doc(artifactId).get();
      if (!artifactDoc.exists) {
        logger.warn(`[UNLOCK] Artifact missing | ArtifactID=${artifactId} | UserID=${uid}`);
        return null;
      }

      const artifactData = artifactDoc.data()!;
      const durationMs = artifactData.durationMs;

      if (!durationMs || durationMs <= 0) {
        logger.error(`[UNLOCK] Invalid duration | ArtifactID=${artifactId} | Duration=${durationMs}`);
        return null;
      }

      // 3. Extract Evidence
      const coverageBuffer = after.coverage;
      const hasReachedEnd = after.hasReachedEnd === true;
      const reviewTrackingVersion = after.reviewTrackingVersion;

      if (!coverageBuffer) {
        logger.warn(`[UNLOCK] Missing coverage | ArtifactID=${artifactId} | UserID=${uid}`);
        return null;
      }

      // 4. Validate Coverage (Policy-Aware)
      const result = validateCoverage(
        durationMs,
        coverageBuffer as Buffer,
        hasReachedEnd,
        reviewTrackingVersion
      );

      // Sanity Check: Malformed bitset
      if (result.cardinality > result.totalSegments + 8) { // Small buffer for byte alignment
        logger.error(`[UNLOCK] Malformed BitSet | Cardinality=${result.cardinality} | Max=${result.totalSegments}`);
        return null;
      }

      logger.info(`[UNLOCK] Validation | UserID=${uid} | ArtID=${artifactId} | Coverage=${(result.coveragePercent * 100).toFixed(2)}% | Valid=${result.isValid}`);

      // 5. Authoritative Unlock
      if (result.isValid) {
        const policy = getPolicy(reviewTrackingVersion);
        await change.after.ref.update({
          "isCommentUnlocked": true,
          "engagementState.unlocked": true,
          "unlockReason": UnlockReason.LISTENING_THRESHOLD,
          "unlockTimestamp": FieldValue.serverTimestamp(),
          "updatedAt": FieldValue.serverTimestamp(),
          "validationVersion": VALIDATION_VERSION,
          "policyVersion": policy.version,
        });

        logger.info(`[UNLOCK] SUCCESS | UserID=${uid} | ArtifactID=${artifactId}`);
      }

      return null;
    } catch (error) {
      logger.error(`[UNLOCK] FATAL ERROR | ArtifactID=${artifactId} | UserID=${uid}:`, error);
      throw error; // Retry
    }
  });

/**
 * Aggregates unique reports for an artifact to ensure reportCount is derived from truth.
 */
async function aggregateReports(db: admin.firestore.Firestore, artifactId: string) {
  const reportsSnapshot = await db.collection("reports")
    .where("artifactId", "==", artifactId)
    .get();

  const uniqueReporters = new Set<string>();
  let lastReportedAt: admin.firestore.Timestamp | null = null;

  reportsSnapshot.docs.forEach((doc) => {
    const data = doc.data();
    uniqueReporters.add(data.reporterId);
    const createdAt = data.createdAt as admin.firestore.Timestamp;
    if (!lastReportedAt || (createdAt && createdAt.toMillis() > lastReportedAt.toMillis())) {
      lastReportedAt = createdAt;
    }
  });

  return {
    reportCount: uniqueReporters.size,
    lastReportedAt: lastReportedAt || FieldValue.serverTimestamp(),
  };
}

/**
 * Aggregates safety concerns for an artifact.
 */
async function aggregateSafetyConcerns(db: admin.firestore.Firestore, artifactId: string) {
  const feedbackSnapshot = await db.collection("feedback_private")
    .where("artifactId", "==", artifactId)
    .where("type", "==", "SAFETY_CONCERN")
    .get();

  return feedbackSnapshot.size;
}

/**
 * Triggered on any write to a report.
 * Ensures the reporter's private suppression marker and artifact aggregates are consistent.
 * Optimized with incremental counters and transactional idempotency to eliminate O(N^2) reads.
 */
export const onReportWrite = functions.firestore
  .document("reports/{reportId}")
  .onWrite(async (change, context) => {
    const reportId = context.params.reportId;
    const eventId = context.eventId;
    const db = admin.firestore();

    // Deterministic key for idempotency (v2 compatible)
    const idempotencyKey = `report_v2_${eventId}`;
    const idempotencyRef = db.collection("idempotency_keys").doc(idempotencyKey);

    const beforeData = change.before.data();
    const afterData = change.after.data();
    const data = afterData || beforeData;

    if (!data) return null;

    const artifactId = data.artifactId;
    const reporterId = data.reporterId;

    if (!artifactId || !reporterId) {
      logger.error("[MODERATION] Report missing required fields", {reportId});
      return null;
    }

    const artifactRef = db.collection("artifacts").doc(artifactId);
    const markerRef = db.collection("users").doc(reporterId)
      .collection("private").doc("reports")
      .collection("artifacts").doc(artifactId);

    return await db.runTransaction(async (transaction) => {
      // 1. Transactional Idempotency Check
      const idempotencyDoc = await transaction.get(idempotencyRef);
      if (idempotencyDoc.exists && idempotencyDoc.data()?.status === "SUCCESS") {
        logger.info(`[MODERATION] Already processed event ${eventId} | ReportId=${reportId}`);
        return null;
      }

      // 2. State Determination
      // CREATE: +1, DELETE: -1, UPDATE: 0
      const delta = (!beforeData && afterData) ? 1 : (beforeData && !afterData) ? -1 : 0;

      // 3. Read Current Artifact State
      const artifactDoc = await transaction.get(artifactRef);
      if (!artifactDoc.exists) {
        logger.warn(`[MODERATION] Artifact missing | ArtifactID=${artifactId}`);
        transaction.set(idempotencyRef, {
          status: "SUCCESS",
          reason: "ARTIFACT_NOT_FOUND",
          createdAt: FieldValue.serverTimestamp(),
        });
        return null;
      }

      const artifactData = artifactDoc.data()!;
      const currentReportCount = artifactData.reportCount || 0;
      const newReportCount = Math.max(0, currentReportCount + delta);

      // 4. Prepare Metadata Updates
      const updates: any = {
        reportCount: newReportCount,
        lastUpdated: FieldValue.serverTimestamp(),
      };

      if (afterData) {
        // Monotonic Update for lastReportedAt
        const newReportedAt = afterData.createdAt || FieldValue.serverTimestamp();
        const currentLastReportedAt = artifactData.lastReportedAt;

        // Note: Firestore Timestamp comparison
        const isNewer = !currentLastReportedAt || (
          newReportedAt instanceof admin.firestore.Timestamp &&
          currentLastReportedAt instanceof admin.firestore.Timestamp &&
          newReportedAt.toMillis() > currentLastReportedAt.toMillis()
        );

        if (isNewer) {
          updates.lastReportedAt = newReportedAt;
        }

        // 5. Authoritative Safety Logic (Suppression)
        if (afterData.reason === "CHILD_SAFETY") {
          updates.recommendationState = ModerationConfig.RecommendationState.SUPPRESSED;
          logger.info(`[MODERATION] CHILD_SAFETY priority suppression | ArtifactID=${artifactId}`);
        } else if (newReportCount >= ModerationConfig.REPORT_SUPPRESSION_THRESHOLD) {
          // Threshold triggered suppression
          if (artifactData.recommendationState !== ModerationConfig.RecommendationState.SUPPRESSED) {
            updates.recommendationState = ModerationConfig.RecommendationState.SUPPRESSED;
            logger.info(`[MODERATION] Threshold suppression | ArtifactID=${artifactId} | Count=${newReportCount}`);
          }
        }
      }

      // Commit Artifact Update
      transaction.update(artifactRef, updates);

      // 6. Marker Lifecycle Management
      if (!afterData) {
        transaction.delete(markerRef);
      } else {
        transaction.set(markerRef, {
          artifactId: artifactId,
          reportedAt: afterData.createdAt || FieldValue.serverTimestamp(),
          reason: afterData.reason,
        });
      }

      // 7. Moderation Queue Synchronization (Atomic)
      if (afterData) {
        const queueRef = db.collection("moderation_queue").doc(artifactId);
        transaction.set(queueRef, {
          artifactId: artifactId,
          reportCount: newReportCount,
          status: ModerationConfig.ModerationStatus.PENDING_REVIEW,
          updatedAt: FieldValue.serverTimestamp(),
        }, {merge: true});
      }

      // 8. Finalize Idempotency
      transaction.set(idempotencyRef, {
        status: "SUCCESS",
        artifactId: artifactId,
        delta: delta,
        createdAt: FieldValue.serverTimestamp(),
      });

      logger.info(`[MODERATION] Aggregation Success | ArtifactID=${artifactId} | NewCount=${newReportCount}`);
      return null;
    });
  });

/**
 * Authoritatively handles private feedback aggregation.
 * Triggered on any write to private feedback to handle updates and deletion.
 * Optimized with incremental counters and transactional idempotency.
 */
export const onPrivateFeedbackWrite = functions.firestore
  .document("feedback_private/{feedbackId}")
  .onWrite(async (change, context) => {
    const eventId = context.eventId;
    const db = admin.firestore();

    const beforeData = change.before.data();
    const afterData = change.after.data();
    const data = afterData || beforeData;

    if (!data || data.type !== "SAFETY_CONCERN") {
      return null;
    }

    const artifactId = data.artifactId;
    const artifactRef = db.collection("artifacts").doc(artifactId);

    // Incremental Idempotency Key
    const idempotencyKey = `sf_agg_v3_${eventId}`;
    const idempotencyRef = db.collection("idempotency_keys").doc(idempotencyKey);

    return await db.runTransaction(async (transaction) => {
      // 1. Transactional Idempotency Check
      const idempotencyDoc = await transaction.get(idempotencyRef);
      if (idempotencyDoc.exists && idempotencyDoc.data()?.status === "SUCCESS") {
        return null;
      }

      // 2. State Determination
      const delta = (!beforeData && afterData) ? 1 : (beforeData && !afterData) ? -1 : 0;

      // 3. Read Current Artifact State
      const artifactDoc = await transaction.get(artifactRef);
      if (!artifactDoc.exists) {
        transaction.set(idempotencyRef, {
          status: "SUCCESS",
          reason: "ARTIFACT_NOT_FOUND",
          createdAt: FieldValue.serverTimestamp(),
        });
        return null;
      }

      const artifactData = artifactDoc.data()!;
      const currentCount = artifactData.safetyConcernCount || 0;
      const newCount = Math.max(0, currentCount + delta);

      const updates: any = {
        safetyConcernCount: newCount,
        lastUpdated: FieldValue.serverTimestamp(),
      };

      // 4. Safety Logic (Threshold Suppression)
      if (newCount >= ModerationConfig.SAFETY_CONCERN_SUPPRESSION_THRESHOLD) {
        if (artifactData.recommendationState !== ModerationConfig.RecommendationState.SUPPRESSED) {
          updates.recommendationState = ModerationConfig.RecommendationState.SUPPRESSED;
          logger.info(`[SAFETY] Suppression triggered | ArtifactID=${artifactId} | Count=${newCount}`);
        }
      }

      transaction.update(artifactRef, updates);

      // 5. Finalize Idempotency
      transaction.set(idempotencyRef, {
        status: "SUCCESS",
        artifactId: artifactId,
        delta: delta,
        createdAt: FieldValue.serverTimestamp(),
      });

      logger.info(`[SAFETY] Aggregate success | ArtifactID=${artifactId} | NewCount=${newCount}`);
      return null;
    });
  });

/**
 * Authoritatively handles comment aggregation when a new comment is created.
 * Wrapped with withIdempotency to prevent duplicate increments on retry.
 */
export const onCommentCreated = functions.firestore
  .document("artifacts/{artifactId}/comments/{commentId}")
  .onCreate(async (snapshot, context) => {
    const artifactId = context.params.artifactId;
    const commentId = context.params.commentId;
    const data = snapshot.data();

    if (!data || data.status === "DELETED") return null;

    return withIdempotency(`comment_inc_${commentId}`, async () => {
      const db = admin.firestore();
      const artifactRef = db.collection("artifacts").doc(artifactId);

      // Fetch Artifact to get ownerId and verify visibility
      const artifactDoc = await artifactRef.get();
      if (!artifactDoc.exists) return;
      const artifactData = artifactDoc.data()!;

      // Privacy Boundary: Block notifications for non-public artifacts
      // Exception: Owner always receives notifications for their own artifacts
      if (!artifactData.isPublic && artifactData.userId !== data.creatorId) {
        logger.info(`[NOTIFICATION] Suppressed for private artifact | ArtifactID=${artifactId}`);
        return;
      }

      const ownerId = artifactData.userId;

      await artifactRef.update({
        commentCount: FieldValue.increment(1),
      });
      logger.info(`[AGGREGATE] commentCount incremented | ArtifactID=${artifactId} | CommentID=${commentId}`);

      // Create Notification if commenter is not the owner
      const commenterId = data.creatorId;
      if (ownerId && ownerId !== commenterId) {
        // Silent Ignore Boundary Check (Phase 6.3.1 Remediation)
        const ignoreDoc = await db.collection("users").doc(ownerId)
          .collection("private").doc("ignored_users")
          .collection("users").doc(commenterId)
          .get();

        if (ignoreDoc.exists) {
          logger.info(`[COMMENT_NOTIF] Suppressed due to ignore | Recipient=${ownerId} | Sender=${commenterId}`);
          return;
        }

        // Authoritative Preference Check
        const userSettingsDoc = await db.collection("users").doc(ownerId)
          .collection("private").doc("settings")
          .get();

        const settings = userSettingsDoc.data();
        if (settings?.notificationsEnabled === false) {
          logger.info(`[NOTIFICATION] Suppressed by user preference | UserID=${ownerId}`);
          return;
        }

        await db.collection("notifications").add({
          userId: ownerId,
          message: `COMMENT|${artifactData.title || "Unknown Artifact"}`,
          artifactId: artifactId,
          type: "COMMENT",
          createdAt: FieldValue.serverTimestamp(),
          isRead: false,
        });
        logger.info(`[NOTIFICATION] Created for owner ${ownerId} | ArtifactID=${artifactId}`);
      }
    });
  });

/**
 * Authoritatively handles comment aggregation when a comment is soft-deleted.
 * Wrapped with withIdempotency to prevent duplicate decrements on retry.
 */
export const onCommentUpdated = functions.firestore
  .document("artifacts/{artifactId}/comments/{commentId}")
  .onUpdate(async (change, context) => {
    const newData = change.after.data();
    const oldData = change.before.data();
    const artifactId = context.params.artifactId;
    const commentId = context.params.commentId;

    if (!newData || !oldData) return null;

    // Detect Soft Delete transition (ACTIVE -> DELETED)
    if (newData.status === "DELETED" && oldData.status !== "DELETED") {
      return withIdempotency(`comment_dec_${commentId}_${context.eventId}`, async () => {
        const db = admin.firestore();
        const artifactRef = db.collection("artifacts").doc(artifactId);

        await artifactRef.update({
          commentCount: FieldValue.increment(-1),
        });

        logger.info(`[AGGREGATE] commentCount decremented | ArtifactID=${artifactId} | CommentID=${commentId}`);
      });
    }

    return null;
  });

/**
 * Authoritatively handles playCount aggregation.
 * Triggered by client logging a daily unique play event to artifact_plays.
 * The playId (play_{userId}_{artifactId}_{date}) ensures idempotency.
 */
export const onPlayCreated = functions.firestore
  .document("artifact_plays/{playId}")
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data) return null;

    const artifactId = data.artifactId;
    const playId = context.params.playId;

    return withIdempotency(playId, async () => {
      const db = admin.firestore();
      const artifactRef = db.collection("artifacts").doc(artifactId);

      await artifactRef.update({
        playCount: FieldValue.increment(1),
      });

      logger.info(`[AGGREGATE] playCount incremented | ArtifactID=${artifactId} | PlayID=${playId}`);
    });
  });

/**
 * Triggers when a new notification document is created in Firestore.
 * Responsible for delivering the push notification via Firebase Cloud Messaging (FCM).
 */
export const onNotificationCreated = functions.firestore
  .document("notifications/{notificationId}")
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data) return null;

    const userId = data.userId;
    if (!userId) return null;

    const db = admin.firestore();

    try {
      // 1. Retrieve the recipient's FCM token from their private settings
      const userSettingsDoc = await db.collection("users").doc(userId)
        .collection("private").doc("settings")
        .get();

      const fcmToken = userSettingsDoc.data()?.fcmToken;

      if (!fcmToken) {
        logger.info(`[FCM] No token found for user ${userId} | NotificationID=${context.params.notificationId}`);
        return null;
      }

      // 2. Map notification data to FCM payload
      const parts = data.message.split("|");
      const key = parts[0];

      let title = "myArtifact";
      let body = "New activity on your profile ✨";

      if (key === "RESONANCE") {
        title = "New Resonance";
        body = "Someone resonated with your artifact 💬";
      } else if (key === "COMMENT") {
        const artifactTitle = parts[1] || "your artifact";
        title = "New Comment";
        body = `Someone shared a thought on "${artifactTitle}" 💬`;
      } else if (key === "PRESENCE_RESONATED") {
        title = "New Presence";
        body = "Someone started following your journey ✨";
      } else if (key === "NEW_ARTIFACT") {
        title = "Artifact Published";
        const artifactTitle = parts[1] || "Your artifact";
        body = `"${artifactTitle}" is now live and resonating.`;
      }

      const message: admin.messaging.Message = {
        token: fcmToken,
        notification: {
          title: title,
          body: body,
        },
        data: {
          artifactId: data.artifactId || "",
          userId: data.followerId || "",
          recipientId: userId, // Internal Recipient Guard (Internal security field)
          notificationType: data.type || "",
          notificationId: context.params.notificationId,
          channelId: key === "PRESENCE_RESONATED" ? "resonances_channel" : "interactions_channel",
        },
        android: {
          priority: key === "PRESENCE_RESONATED" ? "normal" : "high",
          notification: {
            channelId: key === "PRESENCE_RESONATED" ? "resonances_channel" : "interactions_channel",
            sound: key === "PRESENCE_RESONATED" ? undefined : "default",
          }
        }
      };

      // 3. Dispatch the message
      const response = await admin.messaging().send(message);
      logger.info(`[FCM] Successfully sent message: ${response} | UserID=${userId}`);

      return null;
    } catch (error: any) {
      if (error.code === "messaging/registration-token-not-registered") {
        logger.warn(`[FCM] Token expired or invalid for user ${userId}. Should be cleaned up.`);
      } else {
        logger.error(`[FCM] Fatal error sending push for user ${userId}:`, error);
      }
      return null;
    }
  });

/**
 * "Break-Glass" Proxied Reveal for Child Safety Evidence.
 * Allows an authorized Admin to retrieve the Creator's email and a temporary audio link
 * for an artifact that is under Legal Hold and confirmed as a Child Safety violation.
 */
export const revealModerationEvidence = functions.https.onCall(async (data, context) => {
  // 1. Authentication & Admin Authorization
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required.");
  }

  const adminUid = context.auth.uid;
  const artifactId = data.artifactId;

  if (!artifactId || typeof artifactId !== "string") {
    throw new functions.https.HttpsError("invalid-argument", "Artifact ID is required.");
  }

  const db = admin.firestore();

  try {
    // 2. Authoritative Admin Check
    const adminSettings = await db.collection("users").doc(adminUid)
      .collection("private").doc("settings").get();

    if (adminSettings.data()?.isAdmin !== true) {
      logger.warn(`[CEE] Unauthorized access attempt | AdminID=${adminUid} | ArtifactID=${artifactId}`);
      throw new functions.https.HttpsError("permission-denied", "Unauthorized: Admin access required.");
    }

    // 3. Artifact Validation (Exists + Legal Hold)
    const artifactDoc = await db.collection("artifacts").doc(artifactId).get();
    if (!artifactDoc.exists) {
      throw new functions.https.HttpsError("not-found", "Artifact not found.");
    }

    const artifactData = artifactDoc.data()!;
    if (artifactData.moderation?.legalHold !== true) {
      logger.warn(`[CEE] Access denied: No Legal Hold | AdminID=${adminUid} | ArtifactID=${artifactId}`);
      throw new functions.https.HttpsError("failed-precondition", "Artifact is not under Legal Hold.");
    }

    const creatorUid = artifactData.userId;
    if (!creatorUid) {
      throw new functions.https.HttpsError("internal", "Artifact has no owner ID.");
    }

    // 4. Child Safety Case Validation (Authoritative Context)
    // We check if there is at least one report for this artifact with reason CHILD_SAFETY and status RESOLVED.
    const safetyReports = await db.collection("reports")
      .where("artifactId", "==", artifactId)
      .where("reason", "==", "CHILD_SAFETY")
      .where("status", "==", "RESOLVED")
      .limit(1)
      .get();

    if (safetyReports.empty) {
      logger.warn(`[CEE] Access denied: No confirmed CHILD_SAFETY case | AdminID=${adminUid} | ArtifactID=${artifactId}`);
      throw new functions.https.HttpsError("failed-precondition", "No confirmed Child Safety violation found for this artifact.");
    }

    // 5. Data Retrieval: Creator Identity
    const creatorSettings = await db.collection("users").doc(creatorUid)
      .collection("private").doc("settings").get();
    const creatorEmail = creatorSettings.data()?.email;

    // 6. Data Retrieval: Audio Evidence (Signed URL)
    const bucket = admin.storage().bucket();
    const audioPath = `artifacts/${creatorUid}_${artifactId}.m4a`;
    const audioFile = bucket.file(audioPath);

    const [exists] = await audioFile.exists();
    let signedAudioUrl = null;
    let expiresAt = null;

    if (exists) {
      // Configuration: 15 minute duration for review
      const durationMs = 15 * 60 * 1000;
      const expirationDate = new Date(Date.now() + durationMs);
      const [url] = await audioFile.getSignedUrl({
        action: "read",
        expires: expirationDate,
      });
      signedAudioUrl = url;
      expiresAt = expirationDate.toISOString();
    } else {
      logger.warn(`[CEE] Audio file missing for held artifact | Path=${audioPath}`);
    }

    // 7. Audit Log Preparation
    const evidenceScope: string[] = [];
    if (creatorEmail) evidenceScope.push("EMAIL");
    if (exists) evidenceScope.push("AUDIO");

    const status = (creatorEmail && exists) ? "SUCCESS" : "PARTIAL_MISSING_EVIDENCE";
    const adminIp = context.rawRequest?.ip || "unknown";

    // 8. Authoritative Audit Log (Write BEFORE returning data)
    await db.collection("moderation_audit_logs").add({
      adminId: adminUid,
      adminIp: adminIp,
      artifactId: artifactId,
      creatorId: creatorUid,
      action: "EVIDENCE_REVEAL",
      evidenceScope: evidenceScope,
      status: status,
      timestamp: FieldValue.serverTimestamp(),
    });

    logger.info(`[CEE] Evidence Revealed | AdminID=${adminUid} | ArtifactID=${artifactId} | CreatorID=${creatorUid} | Scope=${evidenceScope.join(",")}`);

    return {
      creatorUid: creatorUid,
      creatorEmail: creatorEmail || "email_unavailable",
      audioUrl: signedAudioUrl,
      expiresAt: expiresAt,
      audioStatus: exists ? "AVAILABLE" : "MISSING",
    };
  } catch (error: any) {
    if (error instanceof functions.https.HttpsError) {
      throw error;
    }
    logger.error(`[CEE] FATAL ERROR | ArtifactID=${artifactId} | AdminID=${adminUid}:`, error);
    throw new functions.https.HttpsError("internal", "An internal error occurred during evidence retrieval.");
  }
});

/**
 * Authoritatively heals the moderation counts for an artifact by re-scanning the reports collection.
 * Use this to recover from race conditions or logic errors in the incremental counter.
 */
export const healArtifactModeration = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required.");
  }

  const adminUid = context.auth.uid;
  const artifactId = data.artifactId;

  if (!artifactId || typeof artifactId !== "string") {
    throw new functions.https.HttpsError("invalid-argument", "Artifact ID is required.");
  }

  const db = admin.firestore();

  try {
    // 1. Authoritative Admin Check
    const adminSettings = await db.collection("users").doc(adminUid)
      .collection("private").doc("settings").get();

    if (adminSettings.data()?.isAdmin !== true) {
      logger.warn(`[HEAL] Unauthorized access attempt | AdminID=${adminUid} | ArtifactID=${artifactId}`);
      throw new functions.https.HttpsError("permission-denied", "Unauthorized: Admin access required.");
    }

    // 2. Perform Authoritative Re-scan
    const {reportCount, lastReportedAt} = await aggregateReports(db, artifactId);
    const safetyConcernCount = await aggregateSafetyConcerns(db, artifactId);

    // 3. Update Artifact document
    const artifactRef = db.collection("artifacts").doc(artifactId);
    const artifactDoc = await artifactRef.get();

    if (!artifactDoc.exists) {
      throw new functions.https.HttpsError("not-found", "Artifact not found.");
    }

    const updates: any = {
      reportCount: reportCount,
      safetyConcernCount: safetyConcernCount,
      lastReportedAt: lastReportedAt,
      lastHealedAt: FieldValue.serverTimestamp(),
    };

    // 4. Re-evaluate Suppression State
    if (reportCount >= ModerationConfig.REPORT_SUPPRESSION_THRESHOLD ||
        safetyConcernCount >= ModerationConfig.SAFETY_CONCERN_SUPPRESSION_THRESHOLD) {
      updates.recommendationState = ModerationConfig.RecommendationState.SUPPRESSED;
    }

    await artifactRef.update(updates);

    logger.info(`[HEAL] Moderation counts restored | ArtifactID=${artifactId} | Reports=${reportCount} | Safety=${safetyConcernCount}`);

    return {
      success: true,
      reportCount: reportCount,
      safetyConcernCount: safetyConcernCount,
    };
  } catch (error: any) {
    if (error instanceof functions.https.HttpsError) {
      throw error;
    }
    logger.error(`[HEAL] FATAL ERROR | ArtifactID=${artifactId}:`, error);
    throw new functions.https.HttpsError("internal", "An error occurred during count reconciliation.");
  }
});
