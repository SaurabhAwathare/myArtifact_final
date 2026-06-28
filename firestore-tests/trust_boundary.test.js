const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Trust Boundary Hardening", () => {
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

  async function setupUser(uid, data) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc(uid).set(data);
    });
  }

  async function setupArtifact(artifactId, data) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set(data);
    });
  }

  async function setupEngagement(uid, artifactId, data) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc(uid).collection("engagement").doc(artifactId).set(data);
    });
  }

  it("should prevent artifact creation with forged anonymousId", async () => {
    await setupUser("alice", { anonymousId: "real_alice_id" });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        author: {
          anonymousId: "forged_id",
          name: "Alice",
        },
        isPublic: true,
      })
    );
  });

  it("should allow artifact creation with correct anonymousId", async () => {
    await setupUser("alice", { anonymousId: "real_alice_id" });

    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        author: {
          anonymousId: "real_alice_id",
          name: "Alice",
        },
        isPublic: true,
      })
    );
  });

  it("should prevent comment creation with forged artifactOwnerId", async () => {
    await setupArtifact("art1", { userId: "bob" });
    await setupEngagement("alice", "art1", { isCommentUnlocked: true });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("comments").doc("com1").set({
        authorId: "alice",
        artifactId: "art1",
        artifactOwnerId: "charlie", // Forged
        text: "Hello",
      })
    );
  });

  it("should prevent comment creation with correct artifactOwnerId", async () => {
    await setupArtifact("art1", { userId: "bob" });
    await setupEngagement("alice", "art1", { isCommentUnlocked: true });

    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("comments").doc("com1").set({
        authorId: "alice",
        artifactId: "art1",
        artifactOwnerId: "bob",
        text: "Hello",
      })
    );
  });

  it("should prevent direct modification of artifact aggregates by owner", async () => {
    await setupArtifact("art1", { userId: "alice", reactionCount: 0 });
    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        reactionCount: 1
      })
    );
  });

  it("should prevent direct notification creation", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("notifications").add({
        userId: "alice",
        message: "Spam",
        type: "SYSTEM"
      })
    );
  });

  it("should prevent client from self-unlocking comments in engagement", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art1");

    await setupEngagement("alice", "art1", { isCommentUnlocked: false });

    await assertFails(
      engagementRef.update({
        isCommentUnlocked: true
      })
    );
  });

  it("should allow writing to deep interaction intents", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const intentRef = alice.firestore().collection("users").doc("alice")
      .collection("private").doc("intents")
      .collection("reactions").doc("art1");

    await assertSucceeds(
      intentRef.set({
        artifactId: "art1",
        type: "HEAR",
        action: "ADD",
        timestamp: new Date()
      })
    );
  });

  it("should allow writing to follow intents", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const followRef = alice.firestore().collection("users").doc("alice")
      .collection("private").doc("intents")
      .collection("follow").doc("bob");

    await assertSucceeds(
      followRef.set({
        targetUserId: "bob",
        action: "FOLLOW",
        timestamp: new Date()
      })
    );
  });
});
