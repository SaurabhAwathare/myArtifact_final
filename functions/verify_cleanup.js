const admin = require("firebase-admin");

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: "myartifact-555e3"
  });
}

const db = admin.firestore();
const artifactId = "7f292de4-09d8-48d5-a726-7bfa505eda34";
const userId = "6yPeiwdVuQhG2yjvHJBLKeCqnQi2";

async function verify() {
  console.log(`Verifying artifact: ${artifactId}`);

  // 1. Artifact Document
  const artDoc = await db.collection("artifacts").doc(artifactId).get();
  console.log(`Artifact Document: ${artDoc.exists ? "EXISTS" : "DELETED"}`);
  if (artDoc.exists) {
    console.log(`Status: ${artDoc.data().status}`);
    console.log(`Audio URL: ${artDoc.data().audioUrl}`);
    console.log(`Transcript URL: ${artDoc.data().transcriptUrl}`);
  }

  // 2. Global Reactions
  const reactions = await db.collection("artifact_reactions").where("artifactId", "==", artifactId).get();
  console.log(`Global Reactions Count: ${reactions.size}`);

  // 3. Reaction Counts (Aggregate)
  const countsDoc = await db.collection("artifact_reaction_counts").doc(artifactId).get();
  console.log(`Aggregates Document: ${countsDoc.exists ? "EXISTS" : "DELETED"}`);

  // 4. Ownership Record
  const ownershipDoc = await db.collection("users").doc(userId)
    .collection("private").doc("published_artifacts")
    .collection("artifacts").doc(artifactId)
    .get();
  console.log(`Ownership Record: ${ownershipDoc.exists ? "EXISTS" : "DELETED"}`);

  // 5. Storage Files
  if (artDoc.exists) {
    const audioUrl = artDoc.data().audioUrl;
    if (audioUrl) {
      try {
        const decodedPath = decodeURIComponent(audioUrl.split("/o/")[1].split("?")[0]);
        const file = admin.storage().bucket().file(decodedPath);
        const [exists] = await file.exists();
        console.log(`Storage Audio (${decodedPath}): ${exists ? "EXISTS" : "DELETED"}`);
      } catch (e) {
        console.log(`Error checking storage audio: ${e.message}`);
      }
    }

    const transcriptUrl = artDoc.data().transcriptUrl;
    if (transcriptUrl) {
      try {
        const decodedPath = decodeURIComponent(transcriptUrl.split("/o/")[1].split("?")[0]);
        const file = admin.storage().bucket().file(decodedPath);
        const [exists] = await file.exists();
        console.log(`Storage Transcript (${decodedPath}): ${exists ? "EXISTS" : "DELETED"}`);
      } catch (e) {
        console.log(`Error checking storage transcript: ${e.message}`);
      }
    }
  }

  process.exit(0);
}

verify().catch((err) => {
  console.error(err);
  process.exit(1);
});
