const admin = require("firebase-admin");
admin.initializeApp({
  projectId: "myartifact-555e3"
});

async function run() {
  const snapshot = await admin.firestore().collection("artifacts").limit(1).get();
  if (snapshot.empty) {
    console.log("No artifacts found");
  } else {
    snapshot.forEach((doc) => {
      console.log(doc.id);
    });
  }
}

run().catch(console.error);
