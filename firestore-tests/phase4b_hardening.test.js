const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Phase 4B: Firestore Lifecycle & Ownership Hardening", () => {
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

  it("should ALLOW authenticated user to create PENDING_UPLOAD artifact with empty audioUrl", async () => {
    await setupUser("alice", { anonymousId: "anon_alice" });
    const alice = testEnv.authenticatedContext("alice");

    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        status: "PENDING_UPLOAD",
        audioUrl: "",
        isDraft: true,
        author: { anonymousId: "anon_alice", name: "Alice" },
        playCount: 0,
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0
      })
    );
  });

  it("should REJECT direct ACTIVE creation if audioUrl is empty", async () => {
    await setupUser("alice", { anonymousId: "anon_alice" });
    const alice = testEnv.authenticatedContext("alice");

    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        status: "ACTIVE",
        audioUrl: "",
        isDraft: false,
        author: { anonymousId: "anon_alice", name: "Alice" },
        playCount: 0,
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0
      })
    );
  });

  it("should ALLOW direct ACTIVE creation if audioUrl is present (checkpoint reuse)", async () => {
    await setupUser("alice", { anonymousId: "anon_alice" });
    const alice = testEnv.authenticatedContext("alice");

    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        status: "ACTIVE",
        audioUrl: "https://storage.googleapis.com/artifact.m4a",
        isDraft: false,
        author: { anonymousId: "anon_alice", name: "Alice" },
        playCount: 0,
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0
      })
    );
  });

  it("should REJECT creation with non-zero counts", async () => {
    await setupUser("alice", { anonymousId: "anon_alice" });
    const alice = testEnv.authenticatedContext("alice");

    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").set({
        userId: "alice",
        status: "PENDING_UPLOAD",
        audioUrl: "",
        isDraft: true,
        author: { anonymousId: "anon_alice", name: "Alice" },
        playCount: 100, // MALICIOUS
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0
      })
    );
  });

  it("should REJECT userId change on update", async () => {
    await setupArtifact("art1", { userId: "alice", status: "PENDING_UPLOAD" });
    const alice = testEnv.authenticatedContext("alice");

    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        userId: "bob" // MALICIOUS
      })
    );
  });

  it("should ALLOW PENDING_UPLOAD -> ACTIVE transition with audioUrl", async () => {
    await setupArtifact("art1", {
        userId: "alice",
        status: "PENDING_UPLOAD",
        audioUrl: "",
        isDraft: true
    });
    const alice = testEnv.authenticatedContext("alice");

    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").update({
        status: "ACTIVE",
        audioUrl: "https://storage.googleapis.com/artifact.m4a",
        isDraft: false
      })
    );
  });

  it("should REJECT ACTIVE -> PENDING_UPLOAD regression", async () => {
    await setupArtifact("art1", {
        userId: "alice",
        status: "ACTIVE",
        audioUrl: "https://url.com"
    });
    const alice = testEnv.authenticatedContext("alice");

    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        status: "PENDING_UPLOAD" // REGRESSION
      })
    );
  });

  it("should REJECT audioUrl change once ACTIVE", async () => {
    await setupArtifact("art1", {
        userId: "alice",
        status: "ACTIVE",
        audioUrl: "https://original.com"
    });
    const alice = testEnv.authenticatedContext("alice");

    await assertFails(
      alice.firestore().collection("artifacts").doc("art1").update({
        audioUrl: "https://hacked.com" // SWAP ATTACK
      })
    );
  });

  it("should REJECT cross-user modification", async () => {
    await setupArtifact("art1", { userId: "alice", status: "ACTIVE" });
    const bob = testEnv.authenticatedContext("bob");

    await assertFails(
      bob.firestore().collection("artifacts").doc("art1").update({
        title: "Bob's Hack"
      })
    );
  });
});
