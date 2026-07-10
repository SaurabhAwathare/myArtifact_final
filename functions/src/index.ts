import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import {FieldValue} from "firebase-admin/firestore";
import {withIdempotency} from "./util/idempotency";
import {logger} from "./util/logger";

if (!admin.apps.length) {
  admin.initializeApp();
}

/**
 * Robust cascading cleanup triggered when an artifact is deleted.
 * Handles reactions, aggregates, and metadata.
 * Designed for idempotency and high reliability with recursive batching.
 */
export const onArtifactDeleted = functions.firestore
  .document("artifacts/{artifactId}")
  .onDelete(async (snapshot, context) => {
    const artifactId = context.params.artifactId;
    const db = admin.firestore();
    const eventId = context.eventId;
    const startTime = Date.now();

    logger.info(`[DELETE ARTIFACT] START | ArtifactID=${artifactId} | EventId=${eventId}`);

    // Summary trackers
    let storageDeleted = false;
    const collectionsCleaned: string[] = [];
    let engagementDeletedCount = 0;
    const subCollectionsCleaned: string[] = [];
    let aggregatesDeleted = false;

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
      // 1. Storage Cleanup: Delete the audio file
      const storageStageStart = Date.now();
      logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Storage Cleanup | START`);
      const audioUrl = snapshot.data()?.audioUrl;
      if (audioUrl && audioUrl.includes("firebasestorage")) {
        try {
          const decodedPath = decodeURIComponent(audioUrl.split("/o/")[1].split("?")[0]);
          await admin.storage().bucket().file(decodedPath).delete();
          storageDeleted = true;
          logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | StoragePath=${decodedPath} | DELETED`);
        } catch (e) {
          logger.warn(`[DELETE ARTIFACT] ArtifactID=${artifactId} | StoragePath=${audioUrl} | WARN (Possibly already gone):`, e);
        }
      } else {
        logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | StoragePath=NONE`);
      }
      logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Storage Cleanup | FINISH | Duration=${Date.now() - storageStageStart}ms`);

      // 2. Cleanup top-level collections associated with artifactId via field
      const collections = [
        "artifact_reactions",
        "notifications",
      ];
      for (const col of collections) {
        const colStart = Date.now();
        logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Collection=${col} | START`);
        try {
          let size;
          let totalDeleted = 0;
          do {
            size = await deleteQueryBatch(
              db.collection(col).where("artifactId", "==", artifactId).limit(500)
            );
            if (size > 0) {
              totalDeleted += size;
              logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Collection=${col} | DeletedBatch=${size} | Total=${totalDeleted}`);
            }
          } while (size > 0);
          collectionsCleaned.push(`${col}(${totalDeleted})`);
          logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Collection=${col} | FINISH | Duration=${Date.now() - colStart}ms`);
        } catch (e) {
          logger.error(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Collection=${col} | ERROR:`, e);
        }
      }

      // 3. Cleanup private engagement data for all users who interacted with this artifact
      const engagementStageStart = Date.now();
      logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Engagement Cleanup | START`);
      try {
        const engagementQuery = db.collectionGroup("engagement").where("artifactId", "==", artifactId);
        engagementDeletedCount = await deleteQueryBatch(engagementQuery);
        logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | EngagementDeleted=${engagementDeletedCount}`);
      } catch (e) {
        logger.error(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Engagement Cleanup | ERROR:`, e);
      }
      logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Engagement Cleanup | FINISH | Duration=${Date.now() - engagementStageStart}ms`);

      // 4. Cleanup sub-collections (reactions)
      const subCollections = ["reactions"];
      for (const sub of subCollections) {
        const subStart = Date.now();
        logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Subcollection=${sub} | START`);
        try {
          let size;
          let totalDeleted = 0;
          do {
            size = await deleteQueryBatch(
              snapshot.ref.collection(sub).limit(500)
            );
            if (size > 0) {
              totalDeleted += size;
              logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Subcollection=${sub} | DeletedBatch=${size} | Total=${totalDeleted}`);
            }
          } while (size > 0);
          subCollectionsCleaned.push(`${sub}(${totalDeleted})`);
          logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Subcollection=${sub} | FINISH | Duration=${Date.now() - subStart}ms`);
        } catch (e) {
          logger.error(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Subcollection=${sub} | ERROR:`, e);
        }
      }

      // 5. Cleanup reaction aggregates
      const aggStageStart = Date.now();
      logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Aggregates Cleanup | START`);
      try {
        await db
          .collection("artifact_reaction_counts")
          .doc(artifactId)
          .delete();
        aggregatesDeleted = true;
        logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Aggregates | DELETED`);
      } catch (e) {
        logger.error(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Aggregates Cleanup | ERROR:`, e);
      }
      logger.info(`[DELETE ARTIFACT] ArtifactID=${artifactId} | Stage=Aggregates Cleanup | FINISH | Duration=${Date.now() - aggStageStart}ms`);

      const totalDuration = Date.now() - startTime;
      logger.info(`
==================================
DELETE ARTIFACT SUMMARY
ArtifactID: ${artifactId}
Storage Deleted: ${storageDeleted ? "YES" : "NO"}
Collections Cleaned: ${collectionsCleaned.join(", ")}
Engagement Records: ${engagementDeletedCount}
Sub-collections: ${subCollectionsCleaned.join(", ")}
Aggregates Deleted: ${aggregatesDeleted ? "YES" : "NO"}
Elapsed Total Time: ${totalDuration}ms
==================================
      `);

      return null;
    } catch (error) {
      logger.error(`[DELETE ARTIFACT] ArtifactID=${artifactId} | FATAL ERROR:`, error);
      return null;
    }
  });

/**
 * Updates global reaction aggregates when a new reaction is created.
 * Handles both the dedicated count document and the main artifact metadata.
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

    // 2. Update Main Artifact Metadata (for efficient feed loading)
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
 * Updates markers and counters for both users atomically.
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
          followingCount: FieldValue.increment(1), // Legacy
        });
        transaction.update(targetUserRef, {
          resonanceInCount: FieldValue.increment(1),
          followersCount: FieldValue.increment(1), // Legacy
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
 * Authoritatively handles permanent account cleanup when a user is deleted from Firebase Auth.
 * Triggers cascading deletions across Firestore and Storage while preserving data integrity.
 * Designed for idempotency and resilience.
 */
export const onUserDeleted = functions.auth.user().onDelete(async (user, context) => {
  const uid = user.uid;
  const db = admin.firestore();
  const executionId = Math.random().toString(36).substring(7);
  const eventId = context.eventId;
  const startTime = Date.now();

  logger.info(`[DELETE USER] START | ExecutionId=${executionId} | EventId=${eventId} | UID=${uid}`);
  logger.info(`[DELETE USER] Config | Project=${process.env.GCLOUD_PROJECT} | Timeout=${process.env.FUNCTION_TIMEOUT_SEC}s | Memory=${process.env.FUNCTION_MEMORY_MB}MB | Region=${process.env.FUNCTION_REGION}`);

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
    const artifactStageStart = Date.now();
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Artifact Query | START`);
    const artifactsSnapshot = await db.collection("artifacts").where("userId", "==", uid).get();
    artifactsFound = artifactsSnapshot.size;
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Artifact Query | FINISH | Count=${artifactsFound} | Duration=${Date.now() - artifactStageStart}ms`);

    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Artifact Deletion | START`);
    for (const doc of artifactsSnapshot.docs) {
      const artStart = Date.now();
      logger.info(`[DELETE USER] ExecutionId=${executionId} | ArtifactID=${doc.id} | START`);
      try {
        await doc.ref.delete();
        artifactsDeletedCount++;
        logger.info(`[DELETE USER] ExecutionId=${executionId} | ArtifactID=${doc.id} | FINISH | Duration=${Date.now() - artStart}ms`);
      } catch (e) {
        logger.error(`[DELETE USER] ExecutionId=${executionId} | ArtifactID=${doc.id} | ERROR:`, e);
      }
    }
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Artifact Deletion | FINISH | Duration=${Date.now() - artifactStageStart}ms`);

    // 2. Cleanup Notifications
    const notifStageStart = Date.now();
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Notifications | START`);
    try {
      let notificationsSize;
      do {
        notificationsSize = await deleteQueryBatch(db.collection("notifications").where("userId", "==", uid).limit(500));
        if (notificationsSize > 0) {
          notificationsDeletedTotal += notificationsSize;
          logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Notifications | Deleted=${notificationsSize} | Total=${notificationsDeletedTotal}`);
        }
      } while (notificationsSize > 0);
    } catch (e) {
      logger.error(`[DELETE USER] ExecutionId=${executionId} | Stage=Notifications | ERROR:`, e);
    }
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Notifications | FINISH | Total=${notificationsDeletedTotal} | Duration=${Date.now() - notifStageStart}ms`);

    // 3. Cleanup Resonances (Followers/Following) in other users
    const resonanceStageStart = Date.now();
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Resonance | START`);
    try {
      // resonance_out: users/{uid}/resonance_out/{targetId}
      const resonanceOutSnapshot = await db.collection("users").doc(uid).collection("resonance_out").get();
      logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Resonance | OutboundCount=${resonanceOutSnapshot.size}`);
      for (const doc of resonanceOutSnapshot.docs) {
        const targetId = doc.id;
        const resStart = Date.now();
        try {
          await db.collection("users").doc(targetId).collection("resonance_in").doc(uid).delete();
          await db.collection("users").doc(targetId).update({
            resonanceInCount: FieldValue.increment(-1),
            followersCount: FieldValue.increment(-1),
          });
          resonanceUpdatedCount++;
          logger.info(`[DELETE USER] ExecutionId=${executionId} | ResonanceOut=users/${targetId}/resonance_in/${uid} | FINISH | Duration=${Date.now() - resStart}ms`);
        } catch (e) {
          logger.warn(`[DELETE USER] ExecutionId=${executionId} | ResonanceOut=users/${targetId}/resonance_in/${uid} | ERROR:`, e);
        }
      }

      // resonance_in: users/{uid}/resonance_in/{followerId}
      const resonanceInSnapshot = await db.collection("users").doc(uid).collection("resonance_in").get();
      logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Resonance | InboundCount=${resonanceInSnapshot.size}`);
      for (const doc of resonanceInSnapshot.docs) {
        const followerId = doc.id;
        const resStart = Date.now();
        try {
          await db.collection("users").doc(followerId).collection("resonance_out").doc(uid).delete();
          await db.collection("users").doc(followerId).update({
            resonanceOutCount: FieldValue.increment(-1),
            followingCount: FieldValue.increment(-1),
          });
          resonanceUpdatedCount++;
          logger.info(`[DELETE USER] ExecutionId=${executionId} | ResonanceIn=users/${followerId}/resonance_out/${uid} | FINISH | Duration=${Date.now() - resStart}ms`);
        } catch (e) {
          logger.warn(`[DELETE USER] ExecutionId=${executionId} | ResonanceIn=users/${followerId}/resonance_out/${uid} | ERROR:`, e);
        }
      }
    } catch (e) {
      logger.error(`[DELETE USER] ExecutionId=${executionId} | Stage=Resonance | ERROR:`, e);
    }
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Resonance | FINISH | Duration=${Date.now() - resonanceStageStart}ms`);

    // 4. Cleanup Username reservation
    const usernameStageStart = Date.now();
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Username | START`);
    try {
      const userDoc = await db.collection("users").doc(uid).get();
      const username = userDoc.data()?.anonymousName;
      if (typeof username === "string" && username) {
        await db.collection("usernames").doc(username.toLowerCase().trim()).delete();
        logger.info(`[DELETE USER] ExecutionId=${executionId} | Username=${username} | DELETED`);
      } else {
        logger.info(`[DELETE USER] ExecutionId=${executionId} | Username=NONE`);
      }
    } catch (e) {
      logger.error(`[DELETE USER] ExecutionId=${executionId} | Stage=Username | ERROR:`, e);
    }
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Username | FINISH | Duration=${Date.now() - usernameStageStart}ms`);

    // 5. Cleanup Listening Sessions
    const sessionsStageStart = Date.now();
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Listening Sessions | START`);
    try {
      let sessionsSize;
      do {
        sessionsSize = await deleteQueryBatch(db.collection("listening_sessions").where("userId", "==", uid).limit(500));
        if (sessionsSize > 0) {
          sessionsDeletedTotal += sessionsSize;
          logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Listening Sessions | Deleted=${sessionsSize} | Total=${sessionsDeletedTotal}`);
        }
      } while (sessionsSize > 0);
    } catch (e) {
      logger.error(`[DELETE USER] ExecutionId=${executionId} | Stage=Listening Sessions | ERROR:`, e);
    }
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Listening Sessions | FINISH | Total=${sessionsDeletedTotal} | Duration=${Date.now() - sessionsStageStart}ms`);

    // 6. Final User Document & Subcollections Deletion
    const userDocStageStart = Date.now();
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=User Doc & Subcollections | START`);
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
      const subStart = Date.now();
      try {
        const deleted = await deleteQueryBatch(userRef.collection(sub));
        logger.info(`[DELETE USER] ExecutionId=${executionId} | Subcollection=${sub} | Deleted=${deleted} | Duration=${Date.now() - subStart}ms`);
      } catch (e) {
        logger.warn(`[DELETE USER] ExecutionId=${executionId} | Subcollection=${sub} | ERROR:`, e);
      }
    }

    // Nested private collection cleanup
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
      logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Private Collections | FINISH`);
    } catch (e) {
      logger.error(`[DELETE USER] ExecutionId=${executionId} | Stage=Private Collections | ERROR:`, e);
    }

    try {
      await userRef.delete();
      profileDeleted = true;
      logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=Final User Doc | DELETED`);
    } catch (e) {
      logger.error(`[DELETE USER] ExecutionId=${executionId} | Stage=Final User Doc | ERROR:`, e);
    }
    logger.info(`[DELETE USER] ExecutionId=${executionId} | Stage=User Doc & Subcollections | FINISH | Duration=${Date.now() - userDocStageStart}ms`);

    const totalDuration = Date.now() - startTime;
    logger.info(`
==================================
DELETE USER SUMMARY
ExecutionId: ${executionId}
UID: ${uid}
Artifacts Found: ${artifactsFound}
Artifacts Deleted: ${artifactsDeletedCount}
Notifications Deleted: ${notificationsDeletedTotal}
Resonance Updated: ${resonanceUpdatedCount}
Listening Sessions Deleted: ${sessionsDeletedTotal}
Profile Deleted: ${profileDeleted ? "YES" : "NO"}
Elapsed Total Time: ${totalDuration}ms
==================================
    `);

    return null;
  } catch (error) {
    logger.error(`[DELETE USER] ExecutionId=${executionId} | FATAL ERROR:`, error);
    return null;
  }
});
