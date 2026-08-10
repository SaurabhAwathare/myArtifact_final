const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

const PROJECT_ID = "myartifact-555e3";
const RULES = fs.readFileSync("../firestore.rules", "utf8");

describe("Phase 7G: Artifact Deletion Authorization", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules: RULES,
        host: "127.0.0.1",
        port: 8080,
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      // Setup: Create a user and their artifact
      await db.collection("users").doc("alice").set({ anonymousId: "anon_alice" });
      await db.collection("artifacts").doc("art_alice").set({
        userId: "alice",
        status: "ACTIVE",
        isPublic: true,
        author: { anonymousId: "anon_alice", name: "Alice" },
        createdAt: new Date(),
        audioUrl: "url_alice",
        playCount: 0,
        reactionCount: 0,
        commentCount: 0,
        reportCount: 0
      });
    });
  });

  it("A. Owner -> DELETED: ALLOW", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const db = alice.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertSucceeds(artRef.update({
      status: "DELETED",
      isPublic: false
    }));
  });

  it("B. Non-owner -> DELETED: DENY", async () => {
    const bob = testEnv.authenticatedContext("bob");
    const db = bob.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertFails(artRef.update({
      status: "DELETED",
      isPublic: false
    }));
  });

  it("C. Unauthenticated -> DELETED: DENY", async () => {
    const unauth = testEnv.unauthenticatedContext();
    const db = unauth.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertFails(artRef.update({
      status: "DELETED",
      isPublic: false
    }));
  });

  it("D. Owner attempts direct hard delete: DENY", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const db = alice.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertFails(artRef.delete());
  });

  it("E. Owner attempts DELETED while modifying immutable field (userId): DENY", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const db = alice.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertFails(artRef.update({
      status: "DELETED",
      userId: "hacker"
    }));
  });

  it("F. Owner attempts DELETED while modifying immutable field (createdAt): DENY", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const db = alice.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertFails(artRef.update({
      status: "DELETED",
      createdAt: new Date()
    }));
  });

  it("G. Owner attempts DELETED while modifying immutable field (author): DENY", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const db = alice.firestore();
    const artRef = db.collection("artifacts").doc("art_alice");

    await assertFails(artRef.update({
      status: "DELETED",
      author: { anonymousId: "forged", name: "Alice" }
    }));
  });
});
