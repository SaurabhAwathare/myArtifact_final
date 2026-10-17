const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

describe("Trust Boundary Hardening", () => {
  let testEnv;

  before(async () => {
    const rulesPath = path.join(__dirname, "..", "firestore.rules");
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-trust-boundary-test",
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

  async function setupFixtures(db, { users = [], artifacts = [], engagements = [], registryEntries = [] }) {
    for (const u of users) {
      await db.collection("users").doc(u.uid).set({
        identityMetadata: { identityResetVersion: 0 },
        anonymousId: u.anonymousId || ("anon_" + u.uid),
        anonymousName: u.name || ("User " + u.uid)
      });
      await db.collection("users").doc(u.uid).collection("private").doc("settings").set({
        activeSessionId: "session_" + u.uid,
        accountStatus: "ACTIVE",
        isAdmin: false
      });
    }

    for (const a of artifacts) {
      await db.collection("artifacts").doc(a.id).set(a.data);
    }

    for (const e of engagements) {
      await db.collection("users").doc(e.uid).collection("engagement").doc(e.artifactId).set(e.data);
    }

    for (const r of registryEntries) {
      await db.collection("users").doc(r.uid)
        .collection("private").doc("published_artifacts")
        .collection("artifacts").doc(r.artifactId).set({ createdAt: new Date() });
    }
  }

  it("should prevent artifact creation with forged anonymousId", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }],
        registryEntries: [{ uid: "alice", artifactId: "art1" }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        author: {
          anonymousId: "forged_id",
          name: "Alice",
        },
        status: "PENDING_UPLOAD",
        audioUrl: "",
        isDraft: true,
        playCount: 0,
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0,
        identityVersion: 0,
        identityPropagationVersion: 0,
        isPublic: true,
      })
    );
  });

  it("should allow artifact creation with correct anonymousId and active session", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }],
        registryEntries: [{ uid: "alice", artifactId: "art1" }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        author: {
          anonymousId: "real_alice_id",
          name: "Alice",
        },
        status: "PENDING_UPLOAD",
        audioUrl: "",
        isDraft: true,
        playCount: 0,
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0,
        identityVersion: 0,
        identityPropagationVersion: 0,
        isPublic: true,
      })
    );
  });

  it("should prevent comment creation with forged artifactOwnerId", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "anon1" }],
        artifacts: [{ id: "art1", data: { userId: "bob", isPublic: true } }],
        engagements: [{ uid: "alice", artifactId: "art1", data: { isCommentUnlocked: true } }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").collection("comments").doc("com1").set({
        artifactId: "art1",
        creatorId: "alice",
        author: { anonymousId: "anon1", name: "Alice", sigil: "A" },
        text: "Hello",
        status: "ACTIVE",
        artifactOwnerId: "charlie", // Forged
      })
    );
  });

  it("should block direct comment creation on /comments subcollection for non-admin", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "anon1" }],
        artifacts: [{ id: "art1", data: { userId: "bob", isPublic: true } }],
        engagements: [{ uid: "alice", artifactId: "art1", data: { isCommentUnlocked: true } }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").collection("comments").doc("com1").set({
        artifactId: "art1",
        authorAnonymousId: "anon1",
        author: { anonymousId: "anon1", name: "Alice", sigil: "A" },
        text: "Hello",
        status: "ACTIVE",
      })
    );
  });

  it("should prevent direct modification of artifact aggregates by owner", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }],
        artifacts: [{ id: "art1", data: { userId: "alice", reactionCount: 0, isPublic: true } }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        reactionCount: 1
      })
    );
  });

  it("should prevent direct notification creation", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    await assertFails(
      alice.firestore().collection("notifications").add({
        userId: "alice",
        message: "Spam",
        type: "SYSTEM"
      })
    );
  });

  it("should prevent client from self-unlocking comments in engagement", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }],
        engagements: [{ uid: "alice", artifactId: "art1", data: { isCommentUnlocked: false } }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art1");

    await assertFails(
      engagementRef.update({
        isCommentUnlocked: true
      })
    );
  });

  it("should allow writing to deep interaction intents with active session", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }],
        artifacts: [{ id: "art1", data: { userId: "bob", isPublic: true } }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    const intentRef = alice.firestore().doc("users/alice/private/intents/reactions/art1");

    await assertSucceeds(
      intentRef.set({
        artifactId: "art1",
        type: "HEAR",
        action: "ADD",
        timestamp: new Date()
      })
    );
  });

  it("should allow writing to follow intents with active session", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), {
        users: [{ uid: "alice", anonymousId: "real_alice_id" }]
      });
    });

    const alice = testEnv.authenticatedContext("alice", { activeSessionId: "session_alice" });
    const followRef = alice.firestore().doc("users/alice/private/intents/follow/anon_bob");

    await assertSucceeds(
      followRef.set({
        targetUserId: "bob",
        action: "FOLLOW",
        timestamp: new Date()
      })
    );
  });
});
