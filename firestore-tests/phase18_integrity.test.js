const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Phase 18: System Integrity Verification", () => {
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
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

  async function setupArtifact(artifactId, ownerId) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set({
        userId: ownerId,
        commentCount: 0,
        isPublic: true
      });
    });
  }

  it("VERIFY: Reaction removal protocol alignment", async () => {
      // This is a logic check, emulator rules don't catch "mismatch" but we verify deletion is allowed.
      const alice = testEnv.authenticatedContext("alice");
      const intentRef = alice.firestore().collection("users").doc("alice")
        .collection("private").doc("intents")
        .collection("reactions").doc("art1");

      await testEnv.withSecurityRulesDisabled(async (ctx) => {
          await intentRef.set({ artifactId: "art1", action: "ADD" });
      });

      // Verification: Deletion should succeed
      await assertSucceeds(intentRef.delete());
  });

  it("VERIFY: Notifications created ONLY by backend (Zero-Trust)", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const notificationRef = alice.firestore().collection("notifications").doc("notif1");

    // Client write attempt - MUST FAIL
    await assertFails(
      notificationRef.set({
        userId: "alice",
        message: "ILLEGAL",
        isRead: false
      })
    );
  });

  it("VERIFY: Comment count updated ONLY by backend (Zero-Trust)", async () => {
    await setupArtifact("art1", "bob");

    const alice = testEnv.authenticatedContext("alice");
    const artifactRef = alice.firestore().collection("artifacts").doc("art1");

    // Alice (non-owner) attempts to increment count - MUST FAIL
    await assertFails(
      artifactRef.update({
        commentCount: 1
      })
    );
  });
});
