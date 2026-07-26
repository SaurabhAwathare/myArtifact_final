const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Phase 18: Regression \u0026 Production Readiness Validation", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080
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

  async function setupArtifact(artifactId, ownerId) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set({
        userId: ownerId,
        commentCount: 0,
        playCount: 0,
        reportCount: 0,
        safetyConcernCount: 0,
        isPublic: true,
        author: { anonymousId: "anon_" + ownerId, name: "User " + ownerId }
      });
      await context.firestore().collection("users").doc(ownerId).set({
        anonymousId: "anon_" + ownerId
      });
    });
  }

  it("VERIFY: Zero-Trust - Clients cannot modify aggregate counts", async () => {
    await setupArtifact("art1", "alice");
    const alice = testEnv.authenticatedContext("alice");
    const artRef = alice.firestore().collection("artifacts").doc("art1");
    await assertFails(artRef.update({ commentCount: 1 }));
    await assertFails(artRef.update({ playCount: 1 }));
  });

  it("VERIFY: Play Idempotency - Clients must use specific ID format", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("artifact_plays").doc("play_alice_123").set({
        userId: "alice",
        artifactId: "art1",
        timestamp: new Date()
      })
    );
    await assertFails(
      alice.firestore().collection("artifact_plays").doc("illegal_id").set({
        userId: "alice",
        artifactId: "art1"
      })
    );
  });

  it("VERIFY: Cleanup Protection - Regular users cannot delete artifacts directly", async () => {
    await setupArtifact("art1", "alice");
    const alice = testEnv.authenticatedContext("alice");
    const artRef = alice.firestore().collection("artifacts").doc("art1");
    await assertFails(artRef.delete());
  });

  it("VERIFY: Comment Unlock - Non-owners cannot comment without engagement record", async () => {
    await setupArtifact("art1", "bob");
    const alice = testEnv.authenticatedContext("alice");
    const commentRef = alice.firestore().collection("artifacts").doc("art1").collection("comments").doc("c1");

    await assertFails(
      commentRef.set({
        artifactId: "art1",
        creatorId: "alice",
        text: "Hello",
        status: "ACTIVE",
        author: { anonymousId: "anon_alice", name: "Alice", sigil: "s1" }
      })
    );

    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.firestore().collection("users").doc("alice").set({ anonymousId: "anon_alice" });
        await ctx.firestore().collection("users").doc("alice").collection("engagement").doc("art1").set({
            isCommentUnlocked: true
        });
    });

    await assertSucceeds(
      commentRef.set({
        artifactId: "art1",
        creatorId: "alice",
        text: "Hello",
        status: "ACTIVE",
        author: { anonymousId: "anon_alice", name: "Alice", sigil: "s1" }
      })
    );
  });
});
