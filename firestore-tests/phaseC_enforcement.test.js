const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

describe("Phase C: Firestore Rules Authorization Policy Verification", () => {
  let testEnv;

  before(async () => {
    const rulesPath = path.join(__dirname, "..", "firestore.rules");
    testEnv = await initializeTestEnvironment({
      projectId: "artifact-phase-c",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync(rulesPath, "utf8"),
      },
    });
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  after(async () => {
    await testEnv.cleanup();
  });

  const aliceId = "alice";
  const artifactId = "art1";
  const sessionAlice = "session_alice_123";

  async function setupAlice(db) {
    await db.collection("users").doc(aliceId).set({
      anonymousId: "alice_anon",
      anonymousName: "Alice",
      identityMetadata: { identityResetVersion: 0 }
    });
    await db.collection("users").doc(aliceId).collection("private").doc("settings").set({
      activeSessionId: sessionAlice,
      accountStatus: "ACTIVE",
      isAdmin: false,
    });
    // Create private ownership registry
    await db.collection("users").doc(aliceId)
      .collection("private").doc("published_artifacts")
      .collection("artifacts").doc(artifactId).set({ createdAt: Date.now() });
  }

  it("READ: Authenticated user can read public artifact (Safe Read)", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set({
        isPublic: true,
        status: "ACTIVE",
        userId: "someone_else"
      });
    });

    const alice = testEnv.authenticatedContext(aliceId);
    await assertSucceeds(alice.firestore().collection("artifacts").doc(artifactId).get());
  });

  it("WRITE: Authenticated user with valid active session succeeds in creating artifact", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupAlice(context.firestore());
    });

    const alice = testEnv.authenticatedContext(aliceId, { activeSessionId: sessionAlice });
    const db = alice.firestore();

    await assertSucceeds(db.collection("artifacts").doc(artifactId).set({
      id: artifactId,
      userId: aliceId,
      author: { anonymousId: "alice_anon", name: "Alice" },
      status: "PENDING_UPLOAD",
      audioUrl: "",
      isDraft: true,
      playCount: 0,
      reactionCount: 0,
      commentCount: 0,
      reportCount: 0,
      identityVersion: 0,
      identityPropagationVersion: 0,
      createdAt: Date.now()
    }));
  });

  it("WRITE: Unauthenticated user or missing active session fails to create artifact", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupAlice(context.firestore());
    });

    const unauth = testEnv.unauthenticatedContext();
    await assertFails(unauth.firestore().collection("artifacts").doc(artifactId).set({
      id: artifactId,
      userId: aliceId,
      author: { anonymousId: "alice_anon", name: "Alice" },
      status: "PENDING_UPLOAD",
      audioUrl: "",
      isDraft: true,
      playCount: 0,
      reactionCount: 0,
      commentCount: 0,
      reportCount: 0,
      identityVersion: 0,
      identityPropagationVersion: 0,
      createdAt: Date.now()
    }));

    const noSession = testEnv.authenticatedContext(aliceId); // Missing activeSessionId claim
    await assertFails(noSession.firestore().collection("artifacts").doc(artifactId).set({
      id: artifactId,
      userId: aliceId,
      author: { anonymousId: "alice_anon", name: "Alice" },
      status: "PENDING_UPLOAD",
      audioUrl: "",
      isDraft: true,
      playCount: 0,
      reactionCount: 0,
      commentCount: 0,
      reportCount: 0,
      identityVersion: 0,
      identityPropagationVersion: 0,
      createdAt: Date.now()
    }));
  });

  it("WRITE: Direct creation on /comments subcollection is blocked for non-admin users", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setupAlice(db);
      await db.collection("artifacts").doc(artifactId).set({
        isPublic: true,
        status: "ACTIVE",
        userId: "someone_else"
      });
      await db.collection("users").doc(aliceId).collection("engagement").doc(artifactId).set({
        isCommentUnlocked: true
      });
    });

    const alice = testEnv.authenticatedContext(aliceId, { activeSessionId: sessionAlice });
    const db = alice.firestore();

    await assertFails(db.collection("artifacts").doc(artifactId).collection("comments").doc("c1").set({
      artifactId: artifactId,
      authorAnonymousId: "alice_anon",
      author: { anonymousId: "alice_anon", name: "Alice", sigil: "S" },
      text: "Nice!",
      status: "ACTIVE",
      identityVersion: 0,
      createdAt: Date.now()
    }));
  });

  it("WRITE: Authenticated user with active session and unlocked engagement succeeds in creating comment intent", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setupAlice(db);
      await db.collection("artifacts").doc(artifactId).set({
        isPublic: true,
        status: "ACTIVE",
        userId: "someone_else"
      });
      await db.collection("users").doc(aliceId).collection("engagement").doc(artifactId).set({
        isCommentUnlocked: true
      });
    });

    const alice = testEnv.authenticatedContext(aliceId, { activeSessionId: sessionAlice });
    const db = alice.firestore();

    await assertSucceeds(db.doc(`users/${aliceId}/private/intents/comments/c1`).set({
      artifactId: artifactId,
      text: "Nice!",
    }));
  });

  it("WRITE: Reaction intent creation succeeds for authenticated user with active session on non-owned artifact", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setupAlice(db);
      await db.collection("artifacts").doc("other_art").set({
        userId: "someone_else",
        isPublic: true
      });
    });

    const alice = testEnv.authenticatedContext(aliceId, { activeSessionId: sessionAlice });
    const reactionData = {
      artifactId: "other_art",
      type: "HEAR",
      action: "ADD",
      timestamp: new Date()
    };

    await assertSucceeds(alice.firestore().doc(`users/${aliceId}/private/intents/reactions/other_art`).set(reactionData));

    const unauth = testEnv.unauthenticatedContext();
    await assertFails(unauth.firestore().doc(`users/${aliceId}/private/intents/reactions/other_art`).set(reactionData));
  });
});
