import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import { FieldValue } from "firebase-admin/firestore";
import { withIdempotency } from "./util/idempotency";
import { logger } from "./util/logger";

admin.initializeApp();

/**
 * Triggers when a new reply is added to an artifact.
 * Sends a push notification to the artifact owner.
 */
export const onReplyCreated = functions.firestore
  .document("artifacts/{artifactId}/replies/{replyId}")
  .onCreate(async (snapshot, context) => {
    const artifactId = context.params.artifactId;

    try {
      // 1. Get the artifact document
      const artifactDoc = await admin
        .firestore()
        .collection("artifacts")
        .doc(artifactId)
        .get();

      if (!artifactDoc.exists) {
        console.log(`Artifact ${artifactId} does not exist`);
        return null;
      }

      // 2. Get artifact owner ID
      const artifactData = artifactDoc.data();

      if (!artifactData || !artifactData.userId) {
        console.log("Artifact owner ID not found");
        return null;
      }

      const ownerId = artifactData.userId;

      // 3. Get owner user document
      const userDoc = await admin
        .firestore()
        .collection("users")
        .doc(ownerId)
        .get();

      if (!userDoc.exists) {
        console.log(`User ${ownerId} does not exist`);
        return null;
      }

      // 4. Get FCM token
      const userData = userDoc.data();

      if (!userData || !userData.fcmToken) {
        console.log(`No FCM token found for user ${ownerId}`);
        return null;
      }

      const fcmToken = userData.fcmToken;

      // 5. Create notification payload
      const message: admin.messaging.Message = {
        notification: {
          title: "A Quiet Resonance 🕯️",
          body: "A new reflection has gathered on your artifact",
        },
        data: {
          artifactId: artifactId,
          channelId: "replies_channel",
        },
        token: fcmToken,
        android: {
          priority: "high",
          notification: {
            channelId: "replies_channel",
          },
        },
      };

      // 6. Send push notification
      await admin.messaging().send(message);

      console.log(`Notification sent successfully to user ${ownerId}`);

      return null;
    } catch (error) {
      console.error("Error sending push notification:", error);
      return null;
    }
  });

/**
 * Authoritatively calculates comment unlock state based on listening engagement.
 * Move business logic from client to server for Zero-Trust authorization.
 */
export const onEngagementUpdated = functions.firestore
  .document("users/{userId}/engagement/{artifactId}")
  .onWrite(async (change, context) => {
    const data = change.after.data();
    if (!data) return null;

    const artifactId = context.params.artifactId;
    const userId = context.params.userId;

    // Idempotency: Use a key based on the update timestamp to avoid re-processing the same change
    const updatedAt = data.updatedAt || 0;
    const idempotencyKey = `eng_${userId}_${artifactId}_${updatedAt}`;

    return withIdempotency(idempotencyKey, async () => {
      const db = admin.firestore();
      const artifactRef = db.collection("artifacts").doc(artifactId);
      const artifactDoc = await artifactRef.get();

      if (!artifactDoc.exists) {
        console.log(`Artifact ${artifactId} not found. Skipping.`);
        return null;
      }

      const artifactData = artifactDoc.data();
      const authoritativeDurationMs = artifactData?.durationMs || 0;

      if (authoritativeDurationMs <= 0) {
        console.log(`Artifact ${artifactId} has no duration. Skipping.`);
        return null;
      }

      const clientCoverage = data.coverage;
      const existingData = change.before.data() || {};
      const existingCoverage = existingData.coverage;

      // Aggregation: Multi-device support via BitSet OR
      let mergedCoverage: Buffer;
      if (existingCoverage && clientCoverage) {
        mergedCoverage = mergeBitSets(Buffer.from(existingCoverage), Buffer.from(clientCoverage));
      } else {
        mergedCoverage = Buffer.from(clientCoverage || []);
      }

      // Calculate state based on merged coverage and authoritative duration
      const segmentSizeMs = getSegmentSizeMs(authoritativeDurationMs);
      const totalSegments = Math.max(1, Math.floor(authoritativeDurationMs / segmentSizeMs));
      const setBitsCount = countSetBitsInBuffer(mergedCoverage);
      const coveragePercent = setBitsCount / totalSegments;

      // Verification: Check if threshold met
      const isUnlocked = coveragePercent >= 0.95 && data.hasReachedEnd;

      const oldState = existingData.engagementState;

      // Only update if something changed
      const shouldUpdate = !oldState ||
                           oldState.unlocked !== isUnlocked ||
                           Math.abs((oldState.coveragePercent || 0) - coveragePercent) > 0.001 ||
                           !buffersEqual(existingCoverage, mergedCoverage);

      if (shouldUpdate) {
        console.log(`Updating Engagement: user=${userId}, art=${artifactId}, coverage=${coveragePercent.toFixed(4)}, unlocked=${isUnlocked}`);

        await change.after.ref.update({
          coverage: mergedCoverage,
          isCommentUnlocked: isUnlocked,
          engagementState: {
            unlocked: isUnlocked,
            unlockReason: isUnlocked ? "LISTENING_THRESHOLD_REACHED" : "INSUFFICIENT_ENGAGEMENT",
            unlockVersion: (oldState?.unlockVersion || 0) + 1,
            evaluatedAt: FieldValue.serverTimestamp(),
            coveragePercent: parseFloat(coveragePercent.toFixed(4))
          }
        });
      }

      return { unlocked: isUnlocked, coveragePercent };
    });
  });

/**
 * Merges two bitsets represented as Buffers using bitwise OR.
 */
function mergeBitSets(b1: Buffer, b2: Buffer): Buffer {
  const length = Math.max(b1.length, b2.length);
  const result = Buffer.alloc(length);
  for (let i = 0; i < length; i++) {
    result[i] = (b1[i] || 0) | (b2[i] || 0);
  }
  return result;
}

/**
 * Compares two buffers for equality.
 */
function buffersEqual(b1: any, b2: any): boolean {
  if (!b1 || !b2) return b1 === b2;
  return Buffer.compare(Buffer.from(b1), Buffer.from(b2)) === 0;
}

/**
 * Port of ReviewPolicy.getSegmentSizeMs
 */
function getSegmentSizeMs(durationMs: number): number {
  if (durationMs < 60000) return 500;
  if (durationMs < 600000) return 5000;
  return 10000;
}

/**
 * Counts set bits in a Buffer.
 */
function countSetBitsInBuffer(buffer: Buffer): number {
  let count = 0;
  for (const byte of buffer) {
    count += countSetBits(byte);
  }
  return count;
}

/**
 * Counts the number of set bits (1s) in a byte.
 */
function countSetBits(n: number): number {
  let count = 0;
  let temp = n & 0xff; // Ensure we only treat as a byte
  while (temp > 0) {
    temp &= (temp - 1);
    count++;
  }
  return count;
}

/**
 * Robust cascading cleanup triggered when an artifact is deleted.
 * Handles reactions, comments, replies, aggregates, and metadata.
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
        "comments",
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

      // 4. Cleanup sub-collections (reactions and replies)

      const subCollections = ["reactions", "replies"];
      for (const sub of subCollections) {
        let size;
        do {
          size = await deleteQueryBatch(
            snapshot.ref.collection(sub).limit(500)
          );
          if (size > 0) console.log(`Deleted ${size} sub-docs from ${sub}`);
        } while (size > 0);
      }

      // 3. Cleanup reaction aggregates
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
      { merge: true }
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
      { merge: true }
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

    if (!data || data.action !== 'FOLLOW') return null;

    const idempotencyKey = `follow_${uid}_${targetId}_${data.timestamp?.seconds || 'initial'}`;

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
        transaction.set(resonanceOutRef, { createdAt: timestamp });
        transaction.set(resonanceInRef, { createdAt: timestamp });

        // 2. Update Counters
        transaction.update(currentUserRef, {
          resonanceOutCount: FieldValue.increment(1),
          followingCount: FieldValue.increment(1) // Legacy
        });
        transaction.update(targetUserRef, {
          resonanceInCount: FieldValue.increment(1),
          followersCount: FieldValue.increment(1) // Legacy
        });
      });

      // 3. Create Notification
      await admin.firestore().collection("notifications").add({
        userId: targetId,
        message: "PRESENCE_RESONATED",
        type: "RESONANCE",
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        isRead: false
      });

      logger.interaction("FOLLOW_SUCCESS", { userId: uid, artifactId: targetId }, "SUCCESS");
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
        followingCount: FieldValue.increment(-1)
      });
      transaction.update(targetUserRef, {
        resonanceInCount: FieldValue.increment(-1),
        followersCount: FieldValue.increment(-1)
      });
    });

    logger.interaction("UNFOLLOW_SUCCESS", { userId: uid, artifactId: targetId }, "SUCCESS");
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

    if (!data || data.action !== 'ADD') return null;

    const idempotencyKey = `react_${uid}_${artifactId}_${data.timestamp?.seconds || 'initial'}`;

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
            createdAt: admin.firestore.FieldValue.serverTimestamp()
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
          isRead: false
        });
      }

      logger.interaction("REACTION_SUCCESS", { userId: uid, artifactId: artifactId }, "SUCCESS");
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

    logger.interaction("REACTION_REMOVED", { userId: uid, artifactId: artifactId }, "SUCCESS");
    return null;
  });

/**
 * Authoritatively handles comment creation.
 * Increments artifact comment count and sends notification to owner.
 * Idempotent via commentId.
 */
export const onCommentCreated = functions.firestore
  .document("comments/{commentId}")
  .onCreate(async (snapshot, context) => {
    const data = snapshot.data();
    if (!data) return null;

    const commentId = context.params.commentId;
    const artifactId = data.artifactId;
    const authorId = data.authorId;
    const ownerId = data.artifactOwnerId;

    const idempotencyKey = `comment_${commentId}`;

    return withIdempotency(idempotencyKey, async () => {
      const db = admin.firestore();
      const artifactRef = db.collection("artifacts").doc(artifactId);

      // 1. Increment Count
      await artifactRef.update({
        commentCount: FieldValue.increment(1),
      });

      // 2. Notify Owner (Zero-Trust)
      if (ownerId && ownerId !== authorId) {
        const artifactDoc = await artifactRef.get();
        const title = artifactDoc.data()?.title || "";

        await db.collection("notifications").add({
          userId: ownerId,
          message: title ? `REFLECTION_ARRIVAL|${title}` : "REFLECTION_ARRIVAL_GENERIC",
          artifactId: artifactId,
          type: "REFLECTION",
          createdAt: FieldValue.serverTimestamp(),
          isRead: false,
        });
      }

      logger.info(`Comment count incremented for ${artifactId}`);
    });
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

