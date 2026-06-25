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
      projectId: "demo-artifact",
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

  it("should allow comment creation with correct artifactOwnerId", async () => {
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
});
