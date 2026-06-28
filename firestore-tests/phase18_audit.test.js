const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Phase 18: System Integrity Audit", () => {
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

  // --- DEFECT 1: Notification Writes ---
  it("AUDIT: Should confirm client CANNOT create notifications (Zero-Trust)", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const notificationRef = alice.firestore().collection("notifications").doc("notif1");

    await assertFails(
      notificationRef.set({
        userId: "alice",
        message: "TEST",
        isRead: false
      })
    );
  });

  // --- DEFECT 3: Comment Counter Updates ---
  it("AUDIT: Should confirm client CANNOT increment commentCount on artifacts they do not own", async () => {
    await setupArtifact("art1", "bob"); // Bob owns the artifact

    const alice = testEnv.authenticatedContext("alice");
    const artifactRef = alice.firestore().collection("artifacts").doc("art1");

    // Alice attempts to increment commentCount
    await assertFails(
      artifactRef.update({
        commentCount: 1
      })
    );
  });

  it("AUDIT: Should confirm owner CANNOT increment commentCount (Zero-Trust Enforcement)", async () => {
    await setupArtifact("art1", "alice"); // Alice owns the artifact

    const alice = testEnv.authenticatedContext("alice");
    const artifactRef = alice.firestore().collection("artifacts").doc("art1");

    // Alice (owner) increments commentCount - Should now be DENIED
    await assertFails(
      artifactRef.update({
        commentCount: 1
      })
    );
  });
});
