import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import { FieldValue } from "firebase-admin/firestore";

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

    const coverage = data.coverage; // Buffer from Firestore ByteArray
    const durationMs = data.totalDurationMs;
    const hasReachedEnd = data.hasReachedEnd;

    if (!coverage || durationMs <= 0) {
      console.log(`Skipping evaluation: missing coverage or duration for ${context.params.artifactId}`);
      return null;
    }

    // 1. Calculate segment size (Must match CommentUnlockPolicy.kt)
    let segmentSizeMs = 5000;
    if (durationMs < 60000) {
      segmentSizeMs = 500;
    } else if (durationMs < 600000) {
      segmentSizeMs = 5000;
    } else {
      segmentSizeMs = 10000;
    }

    // 2. Calculate total segments
    const totalSegments = Math.max(1, Math.floor(durationMs / segmentSizeMs));

    // 3. Count set bits in coverage
    let setBitsCount = 0;
    if (Buffer.isBuffer(coverage)) {
      for (const byte of coverage) {
        setBitsCount += countSetBits(byte);
      }
    } else if (coverage instanceof Uint8Array) {
      for (const byte of coverage) {
        setBitsCount += countSetBits(byte);
      }
    } else if (Array.isArray(coverage)) {
      // Handle array of numbers (useful for testing)
      for (const byte of coverage) {
        setBitsCount += countSetBits(byte);
      }
    }

    // 4. Calculate coverage percent and unlock state
    const coveragePercent = setBitsCount / totalSegments;
    const isUnlocked = coveragePercent >= 0.95 && hasReachedEnd;

    const oldState = change.before.data()?.engagementState;

    // 5. Update only if state changed or first time
    if (!oldState || oldState.unlocked !== isUnlocked || Math.abs((oldState.coveragePercent || 0) - coveragePercent) > 0.05) {
      console.log(`Engagement Update: user=${context.params.userId}, artifact=${context.params.artifactId}, coverage=${coveragePercent.toFixed(2)}, unlocked=${isUnlocked}`);

      return change.after.ref.update({
        isCommentUnlocked: isUnlocked, // Compatibility
        engagementState: {
          unlocked: isUnlocked,
          unlockReason: isUnlocked ? "LISTENING_THRESHOLD_REACHED" : "INSUFFICIENT_ENGAGEMENT",
          unlockVersion: (oldState?.unlockVersion || 0) + 1,
          evaluatedAt: FieldValue.serverTimestamp(),
          coveragePercent: parseFloat(coveragePercent.toFixed(4))
        }
      });
    }

    return null;
  });

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
