const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const firebase = require("firebase/compat/app");
require("firebase/compat/firestore");

let testEnv;

describe("Phase 3: Engagement and Comment Authorization", () => {
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-phase3",
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

  async function setupArtifact(artifactId, userId = "bob") {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("artifacts").doc(artifactId).set({
        userId: userId,
        isPublic: true,
        durationMs: 60000,
        createdAt: new Date()
      });
    });
  }

  async function setupUser(uid, anonymousId = "anon1") {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc(uid).set({
        anonymousId: anonymousId,
        anonymousName: "User " + uid
      });
    });
  }

  async function setupEngagement(uid, artifactId, isUnlocked = false) {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore()
        .collection("users").doc(uid)
        .collection("engagement").doc(artifactId)
        .set({
          artifactId: artifactId,
          isCommentUnlocked: isUnlocked,
          lastPositionMs: 1000
        });
    });
  }

  // --- CASE 1: UNLOCKED USER ---
  it("should ALLOW comment creation for an unlocked user", async () => {
    const uid = "alice";
    const artifactId = "art1";
    await setupUser(uid);
    await setupArtifact(artifactId);
    await setupEngagement(uid, artifactId, true);

    const alice = testEnv.authenticatedContext(uid);
    const commentRef = alice.firestore()
      .collection("artifacts").doc(artifactId)
      .collection("comments").doc();

    await assertSucceeds(
      commentRef.set({
        artifactId: artifactId,
        creatorId: uid,
        author: { anonymousId: "anon1", name: "Alice", sigil: "A" },
        text: "Great artifact!",
        status: "ACTIVE"
      })
    );
  });

  // --- CASE 2: LOCKED USER ---
  it("should DENY comment creation for a locked user", async () => {
    const uid = "alice";
    const artifactId = "art1";
    await setupUser(uid);
    await setupArtifact(artifactId);
    await setupEngagement(uid, artifactId, false);

    const alice = testEnv.authenticatedContext(uid);
    const commentRef = alice.firestore()
      .collection("artifacts").doc(artifactId)
      .collection("comments").doc();

    await assertFails(
      commentRef.set({
        artifactId: artifactId,
        creatorId: uid,
        author: { anonymousId: "anon1", name: "Alice", sigil: "A" },
        text: "I shouldn't be able to comment.",
        status: "ACTIVE"
      })
    );
  });

  // --- CASE 3: MISSING ENGAGEMENT ---
  it("should DENY comment creation if engagement document is missing", async () => {
    const uid = "alice";
    const artifactId = "art1";
    await setupUser(uid);
    await setupArtifact(artifactId);
    // No setupEngagement here

    const alice = testEnv.authenticatedContext(uid);
    const commentRef = alice.firestore()
      .collection("artifacts").doc(artifactId)
      .collection("comments").doc();

    await assertFails(
      commentRef.set({
        artifactId: artifactId,
        creatorId: uid,
        author: { anonymousId: "anon1", name: "Alice", sigil: "A" },
        text: "Where is my engagement?",
        status: "ACTIVE"
      })
    );
  });

  // --- CASE 4 & 5: PROTECT BACKEND FIELDS ---
  it("should DENY client from setting isCommentUnlocked or unlockTimestamp", async () => {
    const uid = "alice";
    const artifactId = "art1";
    const alice = testEnv.authenticatedContext(uid);
    const engagementRef = alice.firestore()
      .collection("users").doc(uid)
      .collection("engagement").doc(artifactId);

    // Create attempt with forbidden field
    await assertFails(
      engagementRef.set({
        artifactId: artifactId,
        isCommentUnlocked: true // Manual unlock attempt
      })
    );

    // Initial valid create
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("users").doc(uid).collection("engagement").doc(artifactId).set({
            artifactId: artifactId,
            isCommentUnlocked: false
        });
    });

    // Update attempt with forbidden field
    await assertFails(
      engagementRef.update({
        isCommentUnlocked: true
      })
    );

    await assertFails(
      engagementRef.update({
        unlockTimestamp: Date.now()
      })
    );
  });

  // --- CASE 6: CROSS-USER ACCESS ---
  it("should DENY user from modifying another user's engagement", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const bobEngagementRef = alice.firestore()
      .collection("users").doc("bob")
      .collection("engagement").doc("art1");

    await assertFails(
      bobEngagementRef.set({
        artifactId: "art1",
        lastPositionMs: 5000
      })
    );
  });

  // --- CASE 7: FORGED COMMENT PAYLOAD ---
  it("should DENY comment creation if creatorId does not match auth.uid", async () => {
    const uid = "alice";
    const artifactId = "art1";
    await setupUser(uid);
    await setupArtifact(artifactId);
    await setupEngagement(uid, artifactId, true);

    const alice = testEnv.authenticatedContext(uid);
    const commentRef = alice.firestore()
      .collection("artifacts").doc(artifactId)
      .collection("comments").doc();

    await assertFails(
      commentRef.set({
        artifactId: artifactId,
        creatorId: "bob", // Forged ID
        author: { anonymousId: "anon1", name: "Alice", sigil: "A" },
        text: "I am pretending to be Bob.",
        status: "ACTIVE"
      })
    );
  });

  it("should ALLOW client to update listening evidence fields", async () => {
    const uid = "alice";
    const artifactId = "art1";
    await setupUser(uid);
    await setupEngagement(uid, artifactId, false);

    const alice = testEnv.authenticatedContext(uid);
    const engagementRef = alice.firestore()
      .collection("users").doc(uid)
      .collection("engagement").doc(artifactId);

    await assertSucceeds(
      engagementRef.update({
        lastPositionMs: 5000,
        updatedAt: Date.now()
      })
    );
  });
});
