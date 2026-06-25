const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Phase 4: Firestore Rule Improvements", () => {
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

  async function setupArtifact(artifactId, data) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set(data);
    });
  }

  async function setupComment(commentId, data) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("comments").doc(commentId).set(data);
    });
  }

  it("should allow a user to increment commentCount on an artifact", async () => {
    await setupArtifact("art1", { userId: "bob", commentCount: 0 });

    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").update({
        commentCount: 1,
      })
    );
  });

  it("should prevent a user from incrementing commentCount by more than 1", async () => {
    await setupArtifact("art1", { userId: "bob", commentCount: 0 });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        commentCount: 2,
      })
    );
  });

  it("should prevent a user from changing other fields while incrementing commentCount", async () => {
    await setupArtifact("art1", { userId: "bob", commentCount: 0, title: "Old Title" });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        commentCount: 1,
        title: "New Title",
      })
    );
  });

  it("should allow artifact owner to update creatorReaction on a comment", async () => {
    await setupComment("com1", { artifactOwnerId: "bob", authorId: "alice", text: "Hello" });

    const bob = testEnv.authenticatedContext("bob");
    await assertSucceeds(
      bob.firestore().collection("comments").doc("com1").update({
        creatorReaction: "HEART",
      })
    );
  });

  it("should prevent artifact owner from updating other fields on a comment", async () => {
    await setupComment("com1", { artifactOwnerId: "bob", authorId: "alice", text: "Hello" });

    const bob = testEnv.authenticatedContext("bob");
    await assertFails(
      bob.firestore().collection("comments").doc("com1").update({
        text: "Hacked",
      })
    );
  });

  it("should prevent non-owner from updating creatorReaction", async () => {
    await setupComment("com1", { artifactOwnerId: "bob", authorId: "alice", text: "Hello" });

    const charlie = testEnv.authenticatedContext("charlie");
    await assertFails(
      charlie.firestore().collection("comments").doc("com1").update({
        creatorReaction: "HEART",
      })
    );
  });
});
