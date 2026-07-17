const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const firebase = require("firebase/compat/app");
require("firebase/compat/firestore");

let testEnv;

describe("Investigation: Engagement Rule PERMISSION_DENIED", () => {
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "investigation-engagement",
      firestore: {
        rules: fs.readFileSync("../firestore.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  const uid = "user123";
  const artifactId = "art456";

  it("READ: should ALLOW user to read their own engagement", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .set({ isCommentUnlocked: false });
    });

    const user = testEnv.authenticatedContext(uid);
    await assertSucceeds(
      user.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .get()
    );
  });

  it("WRITE (Create): should ALLOW user to create engagement with valid payload", async () => {
    const user = testEnv.authenticatedContext(uid);
    const payload = {
      artifactId: artifactId,
      userId: uid,
      version: "1.0",
      totalDurationMs: 60000,
      audioChecksum: "abc",
      lastPositionMs: 1000,
      furthestPositionMs: 1000,
      hasReachedEnd: false,
      updatedAt: firebase.firestore.FieldValue.serverTimestamp()
    };

    await assertSucceeds(
      user.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .set(payload)
    );
  });

  it("WRITE (Update): should ALLOW user to update engagement with valid payload (merge)", async () => {
    // Setup existing doc with backend fields
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .set({
          artifactId: artifactId,
          userId: uid,
          isCommentUnlocked: true,
          engagementState: "COMPLETED"
        });
    });

    const user = testEnv.authenticatedContext(uid);
    const payload = {
      lastPositionMs: 5000,
      updatedAt: firebase.firestore.FieldValue.serverTimestamp()
    };

    await assertSucceeds(
      user.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .set(payload, { merge: true })
    );
  });

  it("WRITE (Update): should DENY if user tries to overwrite backend fields", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore()
          .collection("users").doc(uid)
          .collection("engagement").doc(artifactId)
          .set({
            artifactId: artifactId,
            userId: uid,
            isCommentUnlocked: false
          });
      });

    const user = testEnv.authenticatedContext(uid);
    await assertFails(
      user.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .update({
          isCommentUnlocked: true
        })
    );
  });
});
