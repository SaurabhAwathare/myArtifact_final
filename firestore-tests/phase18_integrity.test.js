const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

let testEnv;

describe("Phase 18: System Integrity Verification", () => {
  const sessionAlice = "session_alice_123";

  before(async () => {
    const rulesPath = path.join(__dirname, "..", "firestore.rules");
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-integrity-test",
      firestore: {
        rules: fs.readFileSync(rulesPath, "utf8"),
      },
    });
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  async function setupUser(uid) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await db.collection("users").doc(uid).set({
        anonymousId: "anon_" + uid,
        anonymousName: "User " + uid
      });
      await db.collection("users").doc(uid).collection("private").doc("settings").set({
        activeSessionId: "session_" + uid,
        accountStatus: "ACTIVE",
        isAdmin: false
      });
    });
  }

  async function setupArtifact(artifactId, ownerId) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set({
        userId: ownerId,
        commentCount: 0,
        isPublic: true
      });
    });
  }

  it("VERIFY: Reaction removal protocol alignment with active session", async () => {
      await setupUser("alice");
      const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
      const intentRef = alice.firestore().doc("users/alice/private/intents/reactions/art1");

      await testEnv.withSecurityRulesDisabled(async (ctx) => {
          await ctx.firestore().doc("users/alice/private/intents/reactions/art1").set({ artifactId: "art1", action: "ADD" });
      });

      // Verification: Deletion with active session succeeds
      await assertSucceeds(intentRef.delete());
  });

  it("VERIFY: Notifications created ONLY by backend (Zero-Trust)", async () => {
    await setupUser("alice");
    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
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
    await setupUser("alice");
    await setupArtifact("art1", "bob");

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    const artifactRef = alice.firestore().collection("artifacts").doc("art1");

    // Alice (non-owner) attempts to increment count - MUST FAIL
    await assertFails(
      artifactRef.update({
        commentCount: 1
      })
    );
  });
});
