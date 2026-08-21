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
      // 1. Fetch at most 500 documents (limit of one WriteBatch)
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

      // 3. Delete the fetched documents in one WriteBatch
      const batch = db.batch();
      querySnapshot.docs.forEach((doc) => batch.delete(doc.ref));

      // 4. Commit the batch
      await batch.commit();

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
 * Robust cascading cleanup triggered when an artifact's status changes to DELETED.
 * Handles Storage files, reactions, aggregates, metadata, and final document deletion.
 * Designed for idempotency and high reliability.
 */
export const onArtifactCleanupTrigger = functions.firestore
  .document("artifacts/{artifactId}")
  .onUpdate(async (change, context) => {
    const newData = change.after.data();
    const oldData = change.before.data();
    const artifactId = context.params.artifactId;
    const db = admin.firestore();
    const startTime = Date.now();

    // Only trigger if status transitioned to DELETED
    if (newData.status !== "DELETED" || oldData.status === "DELETED") {
      return null;
    }

    logger.info(`[CLEANUP] START | ArtifactID=${artifactId} | EventId=${context.eventId}`);

    // REQUIREMENT 1: Captured-State Deletion (Read all metadata into local variables)
    const audioUrl = newData.audioUrl;
    const transcriptUrl = newData.transcriptUrl;
    const userId = newData.userId;

    try {
      // 1. Storage Cleanup: Audio
      if (audioUrl && audioUrl.includes("firebasestorage")) {
        try {
          const decodedPath = decodeURIComponent(audioUrl.split("/o/")[1].split("?")[0]);
          await admin.storage().bucket().file(decodedPath).delete();
          logger.info(`[CLEANUP] Audio | DELETED | Path=${decodedPath}`);
        } catch (e: any) {
          if (e.code === 404) {
            logger.warn(`[CLEANUP] Audio | ALREADY_GONE | Path=${audioUrl}`);
          } else {
            logger.error("[CLEANUP] Audio | ERROR:", e);
            throw e;
          }
        }
      }

      // 2. Storage Cleanup: Transcript
      if (transcriptUrl && transcriptUrl.includes("firebasestorage")) {
        try {
          const decodedPath = decodeURIComponent(transcriptUrl.split("/o/")[1].split("?")[0]);
          await admin.storage().bucket().file(decodedPath).delete();
          logger.info(`[CLEANUP] Transcript | DELETED | Path=${decodedPath}`);
        } catch (e: any) {
          if (e.code === 404) {
            logger.warn(`[CLEANUP] Transcript | ALREADY_GONE | Path=${transcriptUrl}`);
          } else {
            logger.error("[CLEANUP] Transcript | ERROR:", e);
            throw e;
          }
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

    console.log(`Incrementing counts for artifact ${artifactId}, type ${typeId}`);

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

    try {
      await batch.commit();
      console.log(`Successfully updated counts for artifact ${artifactId}`);
    } catch (error) {
      console.error(`Failed to update counts for artifact ${artifactId}:`, error);
    }

    return null;
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

    console.log(`Decrementing counts for artifact ${artifactId}, type ${typeId}`);

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

    try {
      await batch.commit();
      console.log(`Successfully decremented counts for artifact ${artifactId}`);
    } catch (error) {
      console.error(`Failed to decrement counts for artifact ${artifactId}:`, error);
    }

    return null;
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
 * Authoritatively handles permanent account cleanup when a user is deleted.
 * Hardened for idempotency and scalability with increased timeout and memory.
 */
export const onUserDeleted = functions
  .runWith({
    timeoutSeconds: 540,
    memory: "1GB",
  })
  .auth.user()
  .onDelete(async (user, context) => {
  const uid = user.uid;
  const db = admin.firestore();
  const startTime = Date.now();

  logger.info(`[DELETE USER] START | UID=${uid}`);

  // Summary trackers
  let artifactsDeletedCount = 0;
  let notificationsDeletedTotal = 0;
  let resonanceUpdatedCount = 0;
  let sessionsDeletedTotal = 0;
  let profileDeleted = false;

  try {
    // 1. Cleanup Artifacts
    const artifactsSnapshot = await db.collection("artifacts").where("userId", "==", uid).get();

    for (const doc of artifactsSnapshot.docs) {
      try {
        if (doc.data().status !== "DELETED") {
          await doc.ref.update({
            status: "DELETED",
            isPublic: false,
            deletedAt: FieldValue.serverTimestamp(),
          });
        }
        artifactsDeletedCount++;
      } catch (e) {
        logger.error(`[DELETE USER] ArtifactID=${doc.id} | ERROR:`, e);
      }
    }

    // 2. Cleanup Notifications
    try {
      notificationsDeletedTotal = await deleteQueryBatch(
        db,
        db.collection("notifications").where("userId", "==", uid),
        "User Notifications"
      );
    } catch (e) {
      logger.error("[DELETE USER] Stage=Notifications | ERROR:", e);
    }

    // 3. Cleanup Resonances (Hardened for Idempotency)
    try {
      const resonanceOutSnapshot = await db.collection("users").doc(uid).collection("resonance_out").get();
      // Process in small parallel batches to avoid timeout while staying under rate limits
      const outBatches = [];
      const outDocs = resonanceOutSnapshot.docs;
      for (let i = 0; i < outDocs.size; i += 10) {
        const chunk = outDocs.slice(i, i + 10);
        outBatches.push(Promise.all(chunk.map(async (doc) => {
          const targetId = doc.id;
          try {
            await db.runTransaction(async (transaction) => {
              const sourceRef = db.collection("users").doc(uid).collection("resonance_out").doc(targetId);
              const sourceDoc = await transaction.get(sourceRef);
              if (!sourceDoc.exists) return; // Already processed

              transaction.delete(sourceRef);
              transaction.delete(db.collection("users").doc(targetId).collection("resonance_in").doc(uid));
              transaction.update(db.collection("users").doc(targetId), {
                resonanceInCount: FieldValue.increment(-1),
                followersCount: FieldValue.increment(-1),
              });
            });
            resonanceUpdatedCount++;
          } catch (e) {
            logger.warn(`[DELETE USER] ResonanceOut=users/${targetId}/resonance_in/${uid} | ERROR:`, e);
          }
        })));
      }
      await Promise.all(outBatches);

      const resonanceInSnapshot = await db.collection("users").doc(uid).collection("resonance_in").get();
      const inBatches = [];
      const inDocs = resonanceInSnapshot.docs;
      for (let i = 0; i < inDocs.size; i += 10) {
        const chunk = inDocs.slice(i, i + 10);
        inBatches.push(Promise.all(chunk.map(async (doc) => {
          const followerId = doc.id;
          try {
            await db.runTransaction(async (transaction) => {
              const sourceRef = db.collection("users").doc(uid).collection("resonance_in").doc(followerId);
              const sourceDoc = await transaction.get(sourceRef);
              if (!sourceDoc.exists) return; // Already processed

              transaction.delete(sourceRef);
              transaction.delete(db.collection("users").doc(followerId).collection("resonance_out").doc(uid));
              transaction.update(db.collection("users").doc(followerId), {
                resonanceOutCount: FieldValue.increment(-1),
                followingCount: FieldValue.increment(-1),
              });
            });
            resonanceUpdatedCount++;
          } catch (e) {
            logger.warn(`[DELETE USER] ResonanceIn=users/${followerId}/resonance_out/${uid} | ERROR:`, e);
          }
        })));
      }
      await Promise.all(inBatches);
    } catch (e) {
      logger.error("[DELETE USER] Stage=Resonance | ERROR:", e);
    }

    // 4. Cleanup Username
    try {
      const userDoc = await db.collection("users").doc(uid).get();
      const username = userDoc.data()?.anonymousName;
      if (typeof username === "string" && username) {
        await db.collection("usernames").doc(username.toLowerCase().trim()).delete();
      }
    } catch (e) {
      logger.error("[DELETE USER] Stage=Username | ERROR:", e);
    }

    // 5. Cleanup Listening Sessions
    try {
      sessionsDeletedTotal = await deleteQueryBatch(
        db,
        db.collection("listening_sessions").where("userId", "==", uid),
        "User Listening Sessions"
      );
    } catch (e) {
      logger.error("[DELETE USER] Stage=Listening Sessions | ERROR:", e);
    }

    // 5.5 Anonymize Comments (Preserve conversation integrity while severing UID link)
    try {
      let commentsAnonymizedCount = 0;
      let commentsSize;
      do {
        const snapshot = await db.collectionGroup("comments")
          .where("creatorId", "==", uid)
          .limit(500)
          .get();

        commentsSize = snapshot.size;
        if (commentsSize > 0) {
          const batch = db.batch();
          snapshot.docs.forEach((doc) => batch.update(doc.ref, {creatorId: ""}));
          await batch.commit();
          commentsAnonymizedCount += commentsSize;
        }
      } while (commentsSize > 0);
      logger.info(`[DELETE USER] Stage=Anonymize Comments | Count=${commentsAnonymizedCount}`);
    } catch (e) {
      logger.error("[DELETE USER] Stage=Anonymize Comments | ERROR:", e);
    }

    // 6. Final User Document & Subcollections Deletion
    const userRef = db.collection("users").doc(uid);
    const subCollections = [
      "engagement",
      "savedArtifacts",
      "resonance_in",
      "resonance_out",
      "followers",
      "following",
      "recommendation_profiles",
    ];

    for (const sub of subCollections) {
      try {
        await deleteQueryBatch(db, userRef.collection(sub), `User Subcollection: ${sub}`);
      } catch (e) {
        logger.warn(`[DELETE USER] Subcollection=${sub} | ERROR:`, e);
      }
    }

    // 7. Cleanup Global Reactions
    try {
      await deleteQueryBatch(
        db,
        db.collection("reactions_global").where("userId", "==", uid),
        "User Global Reactions"
      );
    } catch (e) {
      logger.error("[DELETE USER] Stage=Global Reactions | ERROR:", e);
    }

    // 8. Cleanup Artifact Reactions
    try {
      await deleteQueryBatch(
        db,
        db.collection("artifact_reactions").where("userId", "==", uid),
        "User Artifact Reactions"
      );
    } catch (e) {
      logger.error("[DELETE USER] Stage=Artifact Reactions | ERROR:", e);
    }

    // 9. Cleanup Plays
    try {
      await deleteQueryBatch(
        db,
        db.collection("artifact_plays").where("userId", "==", uid),
        "User Artifact Plays"
      );
    } catch (e) {
      logger.error("[DELETE USER] Stage=Plays | ERROR:", e);
    }

    try {
      const privateRef = userRef.collection("private");
      await deleteQueryBatch(db, privateRef.doc("intents").collection("follow"), "User Intent: Follow");
      await deleteQueryBatch(db, privateRef.doc("intents").collection("reactions"), "User Intent: Reactions");
      await deleteQueryBatch(db, privateRef.doc("interactions").collection("reactions"), "User Interaction: Reactions");
      await deleteQueryBatch(db, privateRef.doc("blocks").collection("users"), "User Blocks");

      await privateRef.doc("settings").delete();
      await privateRef.doc("intents").delete();
      await privateRef.doc("interactions").delete();
      await privateRef.doc("blocks").delete();
    } catch (e) {
      logger.error("[DELETE USER] Stage=Private Collections | ERROR:", e);
    }

    try {
      await userRef.delete();
      profileDeleted = true;
    } catch (e) {
      logger.error("[DELETE USER] Stage=Final User Doc | ERROR:", e);
    }

    const totalDuration = Date.now() - startTime;
    logger.info(`[DELETE USER] FINISH | Artifacts Deleted: ${artifactsDeletedCount} | Notifications: ${notificationsDeletedTotal} | Sessions: ${sessionsDeletedTotal} | Profile Deleted: ${profileDeleted ? "YES" : "NO"} | Duration=${totalDuration}ms`);

    return null;
  } catch (error) {
    logger.error(`[DELETE USER] FATAL ERROR | UID=${uid}:`, error);
    return null;
  }
});

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
 * Evaluates the moderation state of an artifact based on report count.
 */
function evaluateModerationState(reportCount: number) {
  if (reportCount >= ModerationConfig.REPORT_SUPPRESSION_THRESHOLD) {
    return ModerationConfig.RecommendationState.SUPPRESSED;
  }
  return ModerationConfig.RecommendationState.ACTIVE;
}

/**
 * Triggered when a community report is created.
 * Aggregates reports, evaluates moderation threshold, and updates artifact metadata.
 */
export const onReportCreated = functions.firestore
  .document("reports/{reportId}")
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data) return null;

    const artifactId = data.artifactId;
    if (!artifactId) {
      logger.error("[MODERATION] Report missing artifactId", {reportId: context.params.reportId});
      return null;
    }

    const idempotencyKey = `report_agg_v1_${context.params.reportId}`;

    return withIdempotency(idempotencyKey, async () => {
      const db = admin.firestore();
      const artifactRef = db.collection("artifacts").doc(artifactId);
      const queueRef = db.collection("moderation_queue").doc(artifactId);

      // 1. Verify Artifact exists
      const artifactDoc = await artifactRef.get();
      if (!artifactDoc.exists) {
        logger.warn(`[MODERATION] Artifact not found | ID=${artifactId}`);
        return;
      }

      // 2. Aggregate derived data from the source of truth (reports collection)
      const {reportCount, lastReportedAt} = await aggregateReports(db, artifactId);

      // 3. Evaluate moderation state
      const newState = evaluateModerationState(reportCount);
      const currentData = artifactDoc.data()!;

      const batch = db.batch();

      // 4. Update Artifact Metadata (Derived)
      const updates: any = {
        reportCount: reportCount,
        lastReportedAt: lastReportedAt,
      };

      if (newState === ModerationConfig.RecommendationState.SUPPRESSED &&
          currentData.recommendationState !== ModerationConfig.RecommendationState.SUPPRESSED) {
        updates.recommendationState = ModerationConfig.RecommendationState.SUPPRESSED;
        logger.info(`[MODERATION] Suppression triggered | ArtifactID=${artifactId} | Count=${reportCount}`);
      }

      batch.update(artifactRef, updates);

      // 5. Update Moderation Queue
      batch.set(queueRef, {
        artifactId: artifactId,
        reportCount: reportCount,
        status: ModerationConfig.ModerationStatus.PENDING_REVIEW,
        createdAt: currentData.createdAt || FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      }, {merge: true});

      await batch.commit();
      logger.info(`[MODERATION] Aggregation success | ArtifactID=${artifactId} | Count=${reportCount}`);
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

      // Create Notification if commenter is not the owner
      const commenterId = data.creatorId;
      if (ownerId && ownerId !== commenterId) {
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

      logger.info(`[AGGREGATE] commentCount incremented | ArtifactID=${artifactId} | CommentID=${commentId}`);
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
          notificationType: data.type || "",
          notificationId: context.params.notificationId,
        },
        android: {
          priority: "high",
          notification: {
            channelId: "interactions_channel",
            sound: "default",
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
