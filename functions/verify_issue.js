const admin = require("firebase-admin");

// Force connection to emulator if it's running
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: "myartifact-555e3"
  });
}

const db = admin.firestore();
const artifactId = "11b98b35-bc42-4990-bf45-d2e95271028b";

async function verify() {
  console.log(`--- ARTIFACT VERIFICATION: ${artifactId} ---`);

  try {
    const artDoc = await db.collection("artifacts").doc(artifactId).get();

    if (!artDoc.exists) {
      console.log("Artifact Document: NOT FOUND");
      process.exit(0);
    }

    const data = artDoc.data();
    console.log(`status: ${data.status}`);
    console.log(`isPublic: ${data.isPublic}`);
    console.log(`visibility: ${data.visibility}`);
    console.log(`reactionVisibility: ${data.reactionVisibility}`);
    console.log(`userId (ownerId): ${data.userId}`);
    console.log(`createdAt: ${data.createdAt ? (data.createdAt.toDate ? data.createdAt.toDate().toISOString() : data.createdAt) : "null"}`);

    // Also check for reactions
    const reactions = await db.collection("artifact_reactions").where("artifactId", "==", artifactId).get();
    console.log(`Reactions found: ${reactions.size}`);
    if (reactions.size > 0) {
        const rData = reactions.docs[0].data();
        console.log(`Sample Reaction: artifactId=${rData.artifactId}, artifactOwnerId=${rData.artifactOwnerId}, userId=${rData.userId}`);
    }

  } catch (e) {
    console.error("Fetch failed:", e.message);
  }

  console.log("--- End of Verification ---");
  process.exit(0);
}

verify().catch((err) => {
  console.error("Verification failed:", err);
  process.exit(1);
});
