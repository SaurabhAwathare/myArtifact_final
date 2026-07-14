import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import {FieldValue} from "firebase-admin/firestore";
import {withIdempotency} from "./util/idempotency";
import {logger} from "./util/logger";

if (!admin.apps.length) {
  admin.initializeApp();
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

    // Helper to delete all documents returned by a query in batches
    const deleteQueryBatch = async (query: admin.firestore.Query, label: string) => {
      try {
        const querySnapshot = await query.get();
        if (querySnapshot.size === 0) {
          logger.info(`[CLEANUP] ${label} | NONE`);
          return 0;
        }

        const batch = db.batch();
        querySnapshot.docs.forEach((doc) => batch.delete(doc.ref));
        await batch.commit();
        logger.info(`[CLEANUP] ${label} | DELETED Count=${querySnapshot.size}`);
        return querySnapshot.size;
      } catch (e) {
        logger.error(`[CLEANUP] ${label} | ERROR:`, e);
        throw e; // Rethrow to trigger Function retry
      }
    };

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
            logger.error(`[CLEANUP] Audio | ERROR:`, e);
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
            logger.error(`[CLEANUP] Transcript | ERROR:`, e);
            throw e;
          }
        }
      }

      // 3. Comments Cleanup (Subcollection)
      try {
        await db.recursiveDelete(change.after.ref.collection("comments"));
        logger.info(`[CLEANUP] Comments | DELETED`);
      } catch (e) {
        logger.error(`[CLEANUP] Comments | ERROR:`, e);
        throw e;
      }

      // 4. Reactions (Subcollection)
      try {
        await db.recursiveDelete(change.after.ref.collection("reactions"));
        logger.info(`[CLEANUP] Sub-Reactions | DELETED`);
      } catch (e) {
        logger.error(`[CLEANUP] Sub-Reactions | ERROR:`, e);
        throw e;
      }

      // 5. Top-level Reactions (artifact_reactions)
      await deleteQueryBatch(
        db.collection("artifact_reactions").where("artifactId", "==", artifactId),
        "Global Reactions"
      );

      // 6. Reaction Counts (Aggregate)
      try {
        await db.collection("artifact_reaction_counts").doc(artifactId).delete();
        logger.info(`[CLEANUP] Aggregates | DELETED`);
      } catch (e) {
        logger.error(`[CLEANUP] Aggregates | ERROR:`, e);
        throw e;
      }

      // 7. Notifications
      await deleteQueryBatch(
        db.collection("notifications").where("artifactId", "==", artifactId),
        "Notifications"
      );

      // 8. Engagement Records (Collection Group)
      await deleteQueryBatch(
        db.collectionGroup("engagement").where("artifactId", "==", artifactId),
        "Engagement Records"
      );

      // 9. Ownership Record
      if (userId) {
        try {
          await db.collection("users").doc(userId)
            .collection("private").document("published_artifacts")
            .collection("artifacts").document(artifactId)
            .delete();
          logger.info(`[CLEANUP] Ownership Record | DELETED`);
        } catch (e) {
          logger.error(`[CLEANUP] Ownership Record | ERROR:`, e);
          throw e;
        }
      }

      // 10. Private Feedback
      await deleteQueryBatch(
        db.collection("feedback_private").where("artifactId", "==", artifactId),
        "Private Feedback"
      );

      // 11. FINAL: Delete the Artifact document itself
      await change.after.ref.delete();
      logger.info(`[CLEANUP] Artifact Document | DELETED`);

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

      // 3. Create Notification
      await admin.firestore().collection("notifications").add({
        userId: targetId,
        message: "PRESENCE_RESONATED",
        type: "RESONANCE",
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        isRead: false,
      });

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

      // 1. Get Artifact Owner
      const artifactDoc = await db.collection("artifacts").doc(artifactId).get();
      if (!artifactDoc.exists) return;
      const ownerId = artifactDoc.data()?.userId;

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

      // 3. Create Notification for Owner
      if (ownerId && ownerId !== uid) {
        await db.collection("notifications").add({
          userId: ownerId,
          message: `RESONANCE|${data.type}`,
          artifactId: artifactId,
          type: "RESONANCE",
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          isRead: false,
        });
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
 */
export const onUserDeleted = functions.auth.user().onDelete(async (user, context) => {
  const uid = user.uid;
  const db = admin.firestore();
  const executionId = Math.random().toString(36).substring(7);
  const startTime = Date.now();

  logger.info(`[DELETE USER] START | UID=${uid}`);

  // Summary trackers
  let artifactsFound = 0;
  let artifactsDeletedCount = 0;
  let notificationsDeletedTotal = 0;
  let resonanceUpdatedCount = 0;
  let sessionsDeletedTotal = 0;
  let profileDeleted = false;

  // Helper to delete all documents returned by a query in batches
  const deleteQueryBatch = async (query: admin.firestore.Query) => {
    const querySnapshot = await query.get();
    if (querySnapshot.size === 0) return 0;

    const batch = db.batch();
    querySnapshot.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    return querySnapshot.size;
  };

  try {
    // 1. Cleanup Artifacts
    const artifactsSnapshot = await db.collection("artifacts").where("userId", "==", uid).get();
    artifactsFound = artifactsSnapshot.size;

    for (const doc of artifactsSnapshot.docs) {
      try {
        await doc.ref.delete();
        artifactsDeletedCount++;
      } catch (e) {
        logger.error(`[DELETE USER] ArtifactID=${doc.id} | ERROR:`, e);
      }
    }

    // 2. Cleanup Notifications
    try {
      let notificationsSize;
      do {
        notificationsSize = await deleteQueryBatch(db.collection("notifications").where("userId", "==", uid).limit(500));
        if (notificationsSize > 0) {
          notificationsDeletedTotal += notificationsSize;
        }
      } while (notificationsSize > 0);
    } catch (e) {
      logger.error(`[DELETE USER] Stage=Notifications | ERROR:`, e);
    }

    // 3. Cleanup Resonances
    try {
      const resonanceOutSnapshot = await db.collection("users").doc(uid).collection("resonance_out").get();
      for (const doc of resonanceOutSnapshot.docs) {
        const targetId = doc.id;
        try {
          await db.collection("users").doc(targetId).collection("resonance_in").doc(uid).delete();
          await db.collection("users").doc(targetId).update({
            resonanceInCount: FieldValue.increment(-1),
            followersCount: FieldValue.increment(-1),
          });
          resonanceUpdatedCount++;
        } catch (e) {
          logger.warn(`[DELETE USER] ResonanceOut=users/${targetId}/resonance_in/${uid} | ERROR:`, e);
        }
      }

      const resonanceInSnapshot = await db.collection("users").doc(uid).collection("resonance_in").get();
      for (const doc of resonanceInSnapshot.docs) {
        const followerId = doc.id;
        try {
          await db.collection("users").doc(followerId).collection("resonance_out").doc(uid).delete();
          await db.collection("users").doc(followerId).update({
            resonanceOutCount: FieldValue.increment(-1),
            followingCount: FieldValue.increment(-1),
          });
          resonanceUpdatedCount++;
        } catch (e) {
          logger.warn(`[DELETE USER] ResonanceIn=users/${followerId}/resonance_out/${uid} | ERROR:`, e);
        }
      }
    } catch (e) {
      logger.error(`[DELETE USER] Stage=Resonance | ERROR:`, e);
    }

    // 4. Cleanup Username
    try {
      const userDoc = await db.collection("users").doc(uid).get();
      const username = userDoc.data()?.anonymousName;
      if (typeof username === "string" && username) {
        await db.collection("usernames").doc(username.toLowerCase().trim()).delete();
      }
    } catch (e) {
      logger.error(`[DELETE USER] Stage=Username | ERROR:`, e);
    }

    // 5. Cleanup Listening Sessions
    try {
      let sessionsSize;
      do {
        sessionsSize = await deleteQueryBatch(db.collection("listening_sessions").where("userId", "==", uid).limit(500));
        if (sessionsSize > 0) {
          sessionsDeletedTotal += sessionsSize;
        }
      } while (sessionsSize > 0);
    } catch (e) {
      logger.error(`[DELETE USER] Stage=Listening Sessions | ERROR:`, e);
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
        await deleteQueryBatch(userRef.collection(sub));
      } catch (e) {
        logger.warn(`[DELETE USER] Subcollection=${sub} | ERROR:`, e);
      }
    }

    try {
      const privateRef = userRef.collection("private");
      await deleteQueryBatch(privateRef.doc("intents").collection("follow"));
      await deleteQueryBatch(privateRef.doc("intents").collection("reactions"));
      await deleteQueryBatch(privateRef.doc("interactions").collection("reactions"));
      await deleteQueryBatch(privateRef.doc("blocks").collection("users"));

      await privateRef.doc("settings").delete();
      await privateRef.doc("intents").delete();
      await privateRef.doc("interactions").delete();
      await privateRef.doc("blocks").delete();
    } catch (e) {
      logger.error(`[DELETE USER] Stage=Private Collections | ERROR:`, e);
    }

    try {
      await userRef.delete();
      profileDeleted = true;
    } catch (e) {
      logger.error(`[DELETE USER] Stage=Final User Doc | ERROR:`, e);
    }

    const totalDuration = Date.now() - startTime;
    logger.info(`[DELETE USER] FINISH | Artifacts Deleted: ${artifactsDeletedCount} | Profile Deleted: ${profileDeleted ? "YES" : "NO"} | Duration=${totalDuration}ms`);

    return null;
  } catch (error) {
    logger.error(`[DELETE USER] FATAL ERROR | UID=${uid}:`, error);
    return null;
  }
});
