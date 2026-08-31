const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Phase C: App Check Backend Enforcement", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "artifact-phase-c",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("../firestore.rules", "utf8"),
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

  async function setupAlice(db) {
    await db.collection("users").doc(aliceId).set({
      anonymousId: "alice_anon",
      anonymousName: "Alice",
      identityMetadata: { identityResetVersion: 0 }
    });
    // Create private ownership registry
    await db.collection("users").doc(aliceId)
      .collection("private").doc("published_artifacts")
      .collection("artifacts").doc(artifactId).set({ createdAt: Date.now() });
  }

  it("READ: Authenticated user can read public artifact WITHOUT App Check (Safe Read)", async () => {
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

  it("WRITE: Authenticated user WITHOUT App Check fails to create artifact", async () => {
    const alice = testEnv.authenticatedContext(aliceId);
    const db = alice.firestore();

    // Attempt write without App Check
    await assertFails(db.collection("artifacts").doc(artifactId).set({
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
      createdAt: Date.now()
    }));
  });

  it("WRITE: Authenticated user WITH valid App Check succeeds in creating artifact", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupAlice(context.firestore());
    });

    // Simulate App Check token via custom claim for emulator testing
    const aliceWithAppCheck = testEnv.authenticatedContext(aliceId, {
      simulateAppCheck: true
    });
    const db = aliceWithAppCheck.firestore();

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
      createdAt: Date.now()
    }));
  });

  it("WRITE: Authenticated user WITHOUT App Check fails to create comment", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setupAlice(db);
      await db.collection("artifacts").doc(artifactId).set({
        isPublic: true,
        status: "ACTIVE",
        userId: "someone_else"
      });
      // Unlock comments
      await db.collection("users").doc(aliceId).collection("engagement").doc(artifactId).set({
        isCommentUnlocked: true
      });
    });

    const alice = testEnv.authenticatedContext(aliceId);
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

  it("WRITE: Authenticated user WITH valid App Check succeeds in creating comment", async () => {
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

    const aliceWithAppCheck = testEnv.authenticatedContext(aliceId, {
      simulateAppCheck: true
    });
    const db = aliceWithAppCheck.firestore();

    await assertSucceeds(db.collection("artifacts").doc(artifactId).collection("comments").doc("c1").set({
      artifactId: artifactId,
      authorAnonymousId: "alice_anon",
      author: { anonymousId: "alice_anon", name: "Alice", sigil: "S" },
      text: "Nice!",
      status: "ACTIVE",
      identityVersion: 0,
      createdAt: Date.now()
    }));
  });

  it("WRITE: Reactions require App Check", async () => {
    const aliceWithAppCheck = testEnv.authenticatedContext(aliceId, {
      simulateAppCheck: true
    });
    const aliceNoAppCheck = testEnv.authenticatedContext(aliceId);

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupAlice(context.firestore());
    });

    const reactionData = {
      authorAnonymousId: "alice_anon",
      artifactId: artifactId,
      type: "LOVE",
      createdAt: Date.now()
    };

    await assertFails(aliceNoAppCheck.firestore().collection("artifact_reactions").doc("r1").set(reactionData));
    await assertSucceeds(aliceWithAppCheck.firestore().collection("artifact_reactions").doc("r1").set(reactionData));
  });
});
