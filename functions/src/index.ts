import * as functions from "firebase-functions";
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
            // 1. Get the artifact to find the owner's userId
            const artifactDoc = await admin.firestore().collection("artifacts").document(artifactId).get();
            if (!artifactDoc.exists) return;

            const ownerId = artifactDoc.data()?.userId;
            if (!ownerId) return;

            // 2. Get the owner's FCM token from their user document
            const userDoc = await admin.firestore().collection("users").document(ownerId).get();
            const fcmToken = userDoc.data()?.fcmToken;

            if (!fcmToken) {
                console.log(`No FCM token found for user ${ownerId}`);
                return;
            }

            // 3. Construct and send the message
            const message = {
                notification: {
                    title: "New Reply 💬",
                    body: "Someone replied to your artifact",
                },
                token: fcmToken,
                android: {
                    priority: "high" as const,
                    notification: {
                        channelId: "replies_channel",
                    },
                },
            };

            await admin.messaging().send(message);
            console.log(`Notification sent to user ${ownerId}`);

        } catch (error) {
            console.error("Error sending push notification:", error);
        }
    });
