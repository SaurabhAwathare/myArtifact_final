const admin = require("firebase-admin");

// Initialize with project ID
admin.initializeApp({
  projectId: "myartifact-555e3"
});

const db = admin.firestore();
const storage = admin.storage();

async function checkResources(uid) {
  console.log(`Checking resources for UID: ${uid}`);

  // 1. Auth User (handled via CLI export for now, but can check here too)
  try {
    await admin.auth().getUser(uid);
    console.log("✅ Firebase Authentication user exists");
  } catch (error) {
    if (error.code === "auth/user-not-found") {
      console.log("❌ Firebase Authentication user does NOT exist");
    } else {
      console.error("Error checking Auth user:", error.message);
    }
  }

  // 2. Firestore User Document
  const userDoc = await db.collection("users").doc(uid).get();
  console.log(`User Document: ${userDoc.exists ? "✅ Exists" : "❌ Deleted"}`);
  if (userDoc.exists) {
    const data = userDoc.data();
    console.log(`  Username: ${data.anonymousName}`);
  }

  // 3. User Artifacts
  const artifacts = await db.collection("artifacts").where("userId", "==", uid).get();
  console.log(`Artifacts: ${artifacts.size} found`);
  artifacts.forEach((doc) => console.log(`  Artifact ID: ${doc.id}`));

  // 4. Reflections (Comments where user was author)
  const comments = await db.collection("comments").where("authorId", "==", uid).get();
  console.log(`Reflections (Comments): ${comments.size} found`);

  // 5. Notifications
  const notifications = await db.collection("notifications").where("userId", "==", uid).get();
  console.log(`Notifications: ${notifications.size} found`);

  // 6. Listening Sessions
  const sessions = await db.collection("listening_sessions").where("userId", "==", uid).get();
  console.log(`Listening Sessions: ${sessions.size} found`);

  // 7. Username Reservation
  if (userDoc.exists && userDoc.data().anonymousName) {
    const username = userDoc.data().anonymousName.toLowerCase().trim();
    const usernameDoc = await db.collection("usernames").doc(username).get();
    console.log(`Username Reservation (${username}): ${usernameDoc.exists ? "✅ Exists" : "❌ Released"}`);
  }

  // 8. Storage Files
  // Artifacts audio files are typically at artifacts/UID_*.m4a
  const [files] = await storage.bucket().getFiles({ prefix: `artifacts/${uid}_` });
  console.log(`Storage Audio Files: ${files.length} found`);
  files.forEach((file) => console.log(`  File: ${file.name}`));

  const [transcripts] = await storage.bucket().getFiles({ prefix: `transcripts/${uid}_` });
  console.log(`Storage Transcript Files: ${transcripts.length} found`);

  const [backups] = await storage.bucket().getFiles({ prefix: `backups/${uid}/` });
  console.log(`Storage Backup Files: ${backups.length} found`);

  // 9. Resonance Integrity
  // Check if anyone still has this user in their resonance_in or resonance_out
  const resonanceIn = await db.collectionGroup("resonance_in").where(admin.firestore.FieldPath.documentId(), "==", uid).get();
  console.log(`Stale Resonance In references: ${resonanceIn.size} found`);

  const resonanceOut = await db.collectionGroup("resonance_out").where(admin.firestore.FieldPath.documentId(), "==", uid).get();
  console.log(`Stale Resonance Out references: ${resonanceOut.size} found`);
}

const targetUid = process.argv[2] || "rN7lKaC8ujTvjR7Gi0b0u4QfS632";
checkResources(targetUid).catch(console.error);
