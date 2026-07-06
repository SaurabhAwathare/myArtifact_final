const admin = require("firebase-admin");

admin.initializeApp({
  projectId: "myartifact-555e3"
});

const uid = process.argv[2] || "rN7lKaC8ujTvjR7Gi0b0u4QfS632";

async function deleteUser() {
  console.log(`Deleting user from Auth: ${uid}`);
  try {
    await admin.auth().deleteUser(uid);
    console.log("✅ User deleted from Firebase Authentication");
  } catch (error) {
    console.error("Error deleting user:", error.message);
  }
}

deleteUser().catch(console.error);
