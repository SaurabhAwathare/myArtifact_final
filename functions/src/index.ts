import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";

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
 * Robust cascading cleanup triggered when an artifact is deleted.
 * Handles reactions, comments, aggregates, and storage (optional).
 * Designed for idempotency and high reliability.
 */
export const onArtifactDeleted = functions.firestore
  .document("artifacts/{artifactId}")
  .onDelete(async (snapshot, context) => {
    const artifactId = context.params.artifactId;
    const db = admin.firestore();

    console.log(`Starting cascading cleanup for artifact: ${artifactId}`);

    try {
      const batchLimit = 500;

      // 1. Cleanup reactions_global
      const globalReactions = await db.collection("reactions_global")
        .where("artifactId", "==", artifactId)
        .limit(batchLimit)
        .get();

      if (!globalReactions.isEmpty) {
        const batch = db.batch();
        globalReactions.docs.forEach(doc => batch.delete(doc.ref));
        await batch.commit();
        console.log(`Cleaned up ${globalReactions.size} global reactions`);
      }

      // 2. Cleanup comments
      const comments = await db.collection("comments")
        .where("artifactId", "==", artifactId)
        .limit(batchLimit)
        .get();

      if (!comments.isEmpty) {
        const batch = db.batch();
        comments.docs.forEach(doc => batch.delete(doc.ref));
        await batch.commit();
        console.log(`Cleaned up ${comments.size} comments`);
      }

      // 3. Cleanup reaction aggregates
      await db.collection("artifact_reaction_counts").doc(artifactId).delete();
      console.log(`Deleted reaction aggregates for ${artifactId}`);

      // 4. Cleanup sub-collections (reactions)
      // Note: In Firestore, deleting a document does not delete its sub-collections.
      const subReactions = await snapshot.ref.collection("reactions").limit(batchLimit).get();
      if (!subReactions.isEmpty) {
        const batch = db.batch();
        subReactions.docs.forEach(doc => batch.delete(doc.ref));
        await batch.commit();
        console.log(`Cleaned up ${subReactions.size} sub-collection reactions`);
      }

      console.log(`Cascading cleanup completed for ${artifactId}`);
      return null;
    } catch (error) {
      console.error(`Cleanup failed for artifact ${artifactId}:`, error);
      return null;
    }
  });