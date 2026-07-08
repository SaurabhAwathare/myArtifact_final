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

    console.log(`Cascading cleanup for artifact: ${artifactId}`);

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
      const audioUrl = snapshot.data()?.audioUrl;
      if (audioUrl && audioUrl.includes("firebasestorage")) {
        try {
          // Extract file path from download URL
          // Format: https://firebasestorage.googleapis.com/v0/b/BUCKET/o/PATH?alt=media
          const decodedPath = decodeURIComponent(audioUrl.split("/o/")[1].split("?")[0]);
          await admin.storage().bucket().file(decodedPath).delete();
          console.log(`Deleted storage file: ${decodedPath}`);
        } catch (e) {
          console.warn(`Storage deletion failed for ${audioUrl} (possibly already gone):`, e);
        }
      }

      // 2. Cleanup top-level collections associated with artifactId via field
      const collections = [
        "artifact_reactions",
        "notifications",
      ];
      for (const col of collections) {
        let size;
        do {
          size = await deleteQueryBatch(
            db.collection(col).where("artifactId", "==", artifactId).limit(500)
          );
          if (size > 0) console.log(`Deleted ${size} docs from ${col}`);
        } while (size > 0);
      }

      // 3. Cleanup private engagement data for all users who interacted with this artifact
      // This uses a collectionGroup query to find engagement docs across all users
      const engagementQuery = db.collectionGroup("engagement").where("artifactId", "==", artifactId);
      const engagementSize = await deleteQueryBatch(engagementQuery);
      if (engagementSize > 0) console.log(`Deleted ${engagementSize} engagement records via collectionGroup`);

      // 4. Cleanup sub-collections (reactions)
      const subCollections = ["reactions"];
      for (const sub of subCollections) {
        let size;
        do {
          size = await deleteQueryBatch(
            snapshot.ref.collection(sub).limit(500)
          );
          if (size > 0) console.log(`Deleted ${size} sub-docs from ${sub}`);
        } while (size > 0);
      }

      // 5. Cleanup reaction aggregates
      await db
        .collection("artifact_reaction_counts")
        .doc(artifactId)
        .delete();
      console.log(`Deleted reaction aggregates for ${artifactId}`);

      console.log(`Cleanup complete for ${artifactId}`);
      return null;
    } catch (error) {
      console.error(`Cleanup failed for artifact ${artifactId}:`, error);
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
export const onUserDeleted = functions.auth.user().onDelete(async (user) => {
  const uid = user.uid;
  const db = admin.firestore();

  logger.info(`Starting permanent cleanup for user: ${uid}`);

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
    // Deleting an artifact document triggers 'onArtifactDeleted' for full cascading cleanup (Storage, Reactions, etc.)
    const artifactsSnapshot = await db.collection("artifacts").where("userId", "==", uid).get();
    for (const doc of artifactsSnapshot.docs) {
      await doc.ref.delete();
      logger.info(`Deleted user-owned artifact: ${doc.id}`);
    }

    // 2. Cleanup Notifications
    let notificationsSize;
    do {
      notificationsSize = await deleteQueryBatch(db.collection("notifications").where("userId", "==", uid).limit(500));
      if (notificationsSize > 0) logger.info(`Deleted ${notificationsSize} notifications for user: ${uid}`);
    } while (notificationsSize > 0);

    // 3. Cleanup Resonances (Followers/Following) in other users
    // resonance_out: users/{uid}/resonance_out/{targetId}
    const resonanceOutSnapshot = await db.collection("users").doc(uid).collection("resonance_out").get();
    for (const doc of resonanceOutSnapshot.docs) {
      const targetId = doc.id;
      try {
        await db.collection("users").doc(targetId).collection("resonance_in").doc(uid).delete();
        await db.collection("users").doc(targetId).update({
          resonanceInCount: FieldValue.increment(-1),
          followersCount: FieldValue.increment(-1),
        });
        logger.info(`Removed user ${uid} from resonance_in of ${targetId}`);
      } catch (e) {
        logger.warn(`Failed to cleanup resonance_in for target ${targetId}:`, e);
      }
    }

    // resonance_in: users/{uid}/resonance_in/{followerId}
    const resonanceInSnapshot = await db.collection("users").doc(uid).collection("resonance_in").get();
    for (const doc of resonanceInSnapshot.docs) {
      const followerId = doc.id;
      try {
        await db.collection("users").doc(followerId).collection("resonance_out").doc(uid).delete();
        await db.collection("users").doc(followerId).update({
          resonanceOutCount: FieldValue.increment(-1),
          followingCount: FieldValue.increment(-1),
        });
        logger.info(`Removed user ${uid} from resonance_out of ${followerId}`);
      } catch (e) {
        logger.warn(`Failed to cleanup resonance_out for follower ${followerId}:`, e);
      }
    }

    // 4. Cleanup Username reservation
    const userDoc = await db.collection("users").doc(uid).get();
    const username = userDoc.data()?.anonymousName;
    if (typeof username === "string" && username) {
      await db.collection("usernames").doc(username.toLowerCase().trim()).delete();
      logger.info(`Deleted username reservation: ${username}`);
    }

    // 5. Cleanup Listening Sessions
    let sessionsSize;
    do {
      sessionsSize = await deleteQueryBatch(db.collection("listening_sessions").where("userId", "==", uid).limit(500));
      if (sessionsSize > 0) logger.info(`Deleted ${sessionsSize} listening sessions for user: ${uid}`);
    } while (sessionsSize > 0);

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
        logger.warn(`Failed to clear subcollection ${sub} for user ${uid}:`, e);
      }
    }

    // Nested private collection cleanup
    const privateRef = userRef.collection("private");
    await deleteQueryBatch(privateRef.doc("intents").collection("follow"));
    await deleteQueryBatch(privateRef.doc("intents").collection("reactions"));
    await deleteQueryBatch(privateRef.doc("interactions").collection("reactions"));
    await deleteQueryBatch(privateRef.doc("blocks").collection("users"));

    await privateRef.doc("settings").delete();
    await privateRef.doc("intents").delete();
    await privateRef.doc("interactions").delete();
    await privateRef.doc("blocks").delete();

    await userRef.delete();
    logger.info(`Successfully completed cleanup for user: ${uid}`);

    return null;
  } catch (error) {
    logger.error(`Cleanup failed for user ${uid}:`, error);
    return null;
  }
});
