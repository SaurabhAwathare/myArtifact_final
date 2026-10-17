const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

describe("Phase 3: Engagement and Comment Authorization", () => {
  let testEnv;

  before(async () => {
    const rulesPath = path.join(__dirname, "..", "firestore.rules");
    const rules = fs.readFileSync(rulesPath, "utf8");
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-engagement-test",
      firestore: { rules },
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

  async function setupFixtures(db, { uid, artifactId, isUnlocked = false, creatorUid = "bob" }) {
    if (creatorUid) {
      await db.collection("artifacts").doc(artifactId).set({
        userId: creatorUid,
        isPublic: true,
        durationMs: 60000,
        createdAt: new Date()
      });
    }

    if (uid) {
      await db.collection("users").doc(uid).set({
        anonymousId: "anon_" + uid,
        anonymousName: "User " + uid
      });
      await db.collection("users").doc(uid).collection("private").doc("settings").set({
        activeSessionId: "session_" + uid,
        accountStatus: "ACTIVE",
        isAdmin: false
      });

      if (isUnlocked !== null) {
        await db.collection("users").doc(uid).collection("engagement").doc(artifactId).set({
          artifactId: artifactId,
          isCommentUnlocked: isUnlocked,
          lastPositionMs: 1000
        });
      }
    }
  }

  // --- CASE 1: UNLOCKED USER COMMENT INTENT ---
  it("should ALLOW comment intent creation for an unlocked user", async () => {
    const uid = "alice";
    const artifactId = "art1";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), { uid, artifactId, isUnlocked: true });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
    const commentIntentRef = alice.firestore()
      .doc(`users/${uid}/private/intents/comments/c1`);

    await assertSucceeds(
      commentIntentRef.set({
        artifactId: artifactId,
        text: "Great artifact!",
      })
    );
  });

  // --- CASE 2: LOCKED USER COMMENT INTENT ---
  it("should DENY comment intent creation for a locked user", async () => {
    const uid = "alice";
    const artifactId = "art1";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), { uid, artifactId, isUnlocked: false });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
    const commentIntentRef = alice.firestore()
      .doc(`users/${uid}/private/intents/comments/c1`);

    await assertFails(
      commentIntentRef.set({
        artifactId: artifactId,
        text: "I shouldn't be able to comment.",
      })
    );
  });

  // --- CASE 3: MISSING ENGAGEMENT ---
  it("should DENY comment intent creation if engagement document is missing", async () => {
    const uid = "alice";
    const artifactId = "art1";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), { uid, artifactId, isUnlocked: null });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
    const commentIntentRef = alice.firestore()
      .doc(`users/${uid}/private/intents/comments/c1`);

    await assertFails(
      commentIntentRef.set({
        artifactId: artifactId,
        text: "Where is my engagement?",
      })
    );
  });

  // --- CASE 4 & 5: PROTECT BACKEND FIELDS ---
  it("should DENY client from setting isCommentUnlocked or unlockTimestamp", async () => {
    const uid = "alice";
    const artifactId = "art1";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), { uid, artifactId, isUnlocked: null });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
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
    const uid = "alice";
    const otherUid = "bob";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setupFixtures(db, { uid, artifactId: "art1", isUnlocked: false });
      await setupFixtures(db, { uid: otherUid, artifactId: "art1", isUnlocked: false });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
    const bobEngagementRef = alice.firestore()
      .collection("users").doc(otherUid)
      .collection("engagement").doc("art1");

    await assertFails(
      bobEngagementRef.set({
        artifactId: "art1",
        lastPositionMs: 5000
      })
    );
  });

  // --- CASE 7: DIRECT COMMENT CREATION ON SUBCOLLECTION BLOCKED FOR NON-ADMIN ---
  it("should DENY direct comment creation on /comments subcollection for non-admin", async () => {
    const uid = "alice";
    const artifactId = "art1";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), { uid, artifactId, isUnlocked: true });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
    const commentRef = alice.firestore()
      .collection("artifacts").doc(artifactId)
      .collection("comments").doc("c1");

    await assertFails(
      commentRef.set({
        artifactId: artifactId,
        authorAnonymousId: "anon_alice",
        author: { anonymousId: "anon_alice", name: "Alice", sigil: "A" },
        text: "I am trying to bypass intent system.",
        status: "ACTIVE"
      })
    );
  });

  it("should ALLOW client to update listening evidence fields", async () => {
    const uid = "alice";
    const artifactId = "art1";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setupFixtures(context.firestore(), { uid, artifactId, isUnlocked: false });
    });

    const alice = testEnv.authenticatedContext(uid, { activeSessionId: "session_" + uid });
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
