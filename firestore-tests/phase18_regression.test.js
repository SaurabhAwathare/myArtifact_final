const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

describe("Phase 18: Regression & Production Readiness Validation", () => {
  let testEnv;

  before(async () => {
    const rulesPath = path.join(__dirname, "..", "firestore.rules");
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-regression-test",
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

  async function setupFixtures(db, { users = [], artifacts = [], engagements = [] }) {
    for (const u of users) {
      await db.collection("users").doc(u.uid).set({
        anonymousId: "anon_" + u.uid,
        anonymousName: "User " + u.uid
      });
      await db.collection("users").doc(u.uid).collection("private").doc("settings").set({
        activeSessionId: "session_" + u.uid,
        accountStatus: "ACTIVE",
        isAdmin: false
      });
    }

    for (const a of artifacts) {
      await db.collection("artifacts").doc(a.id).set({
        userId: a.ownerId,
        commentCount: 0,
        playCount: 0,
        reportCount: 0,
        safetyConcernCount: 0,
        isPublic: true,
        author: { anonymousId: "anon_" + a.ownerId, name: "User " + a.ownerId }
      });
    }

    for (const e of engagements) {
      await db.collection("users").doc(e.uid).collection("engagement").doc(e.artifactId).set(e.data);
    }
  }

  it("VERIFY: Zero-Trust - Clients cannot modify aggregate counts", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "user1" }],
        artifacts: [{ id: "art1", ownerId: "user1" }]
      });
    });

    const ctx = testEnv.authenticatedContext("user1", { activeSessionId: "session_user1" });
    const db = ctx.firestore();
    const artRef = db.collection("artifacts").doc("art1");
    await assertFails(artRef.update({ commentCount: 1 }));
    await assertFails(artRef.update({ playCount: 1 }));
  });

  it("VERIFY: Play Idempotency - Clients must use specific ID format", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "user2" }]
      });
    });

    const ctx = testEnv.authenticatedContext("user2", { activeSessionId: "session_user2" });
    const db = ctx.firestore();
    await assertSucceeds(
      db.collection("artifact_plays").doc("play_user2_123").set({
        userId: "user2",
        artifactId: "art1",
        timestamp: new Date()
      })
    );
    await assertFails(
      db.collection("artifact_plays").doc("illegal_id").set({
        userId: "user2",
        artifactId: "art1"
      })
    );
  });

  it("VERIFY: Cleanup Protection - Regular users cannot delete artifacts directly", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "user3" }],
        artifacts: [{ id: "art3", ownerId: "user3" }]
      });
    });

    const ctx = testEnv.authenticatedContext("user3", { activeSessionId: "session_user3" });
    const db = ctx.firestore();
    const artRef = db.collection("artifacts").doc("art3");
    await assertFails(artRef.delete());
  });

  it("VERIFY: Comment Intent Unlock - Non-owners cannot comment without engagement record", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "user4" }, { uid: "owner4" }],
        artifacts: [{ id: "art4", ownerId: "owner4" }],
        engagements: [{ uid: "user4", artifactId: "art4", data: { isCommentUnlocked: false } }]
      });
    });

    const ctx = testEnv.authenticatedContext("user4", { activeSessionId: "session_user4" });
    const db = ctx.firestore();
    const commentIntentRef = db.doc("users/user4/private/intents/comments/c1");

    // Denied when isCommentUnlocked: false
    await assertFails(
      commentIntentRef.set({
        artifactId: "art4",
        text: "Hello",
      })
    );
  });

  it("VERIFY: Comment Intent Unlock - Non-owners CAN comment with unlocked engagement record", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "user5" }, { uid: "owner5" }],
        artifacts: [{ id: "art5", ownerId: "owner5" }],
        engagements: [{ uid: "user5", artifactId: "art5", data: { isCommentUnlocked: true } }]
      });
    });

    const ctx = testEnv.authenticatedContext("user5", { activeSessionId: "session_user5" });
    const db = ctx.firestore();
    const commentIntentRef = db.doc("users/user5/private/intents/comments/c1");

    // Allowed when isCommentUnlocked: true
    await assertSucceeds(
      commentIntentRef.set({
        artifactId: "art5",
        text: "Hello",
      })
    );
  });
});
