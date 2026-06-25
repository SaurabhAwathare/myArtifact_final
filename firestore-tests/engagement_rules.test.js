const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Engagement Rules", () => {
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

  it("should allow owner to update progress but NOT unlock status", async () => {
    const alice = testEnv.authenticatedContext("alice");
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art1");

    // Initial set (no restricted fields)
    await assertSucceeds(
      engagementRef.set({
        artifactId: "art1",
        lastPositionMs: 1000,
      })
    );

    // Update progress - Allowed
    await assertSucceeds(
      engagementRef.update({
        lastPositionMs: 2000,
      })
    );

    // Attempt to self-unlock - Denied
    await assertFails(
      engagementRef.update({
        isCommentUnlocked: true,
      })
    );

    // Attempt to manipulate engagementState - Denied
    await assertFails(
      engagementRef.update({
        engagementState: { unlocked: true },
      })
    );
  });

  it("should trigger cloud function to unlock comments", async function() {
    this.timeout(20000);

    const alice = testEnv.authenticatedContext("alice");
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art1");

    // 100% coverage bitset for a 10s audio
    const coverage = [255, 255, 15];

    await assertSucceeds(
      engagementRef.set({
        artifactId: "art1",
        userId: "alice",
        totalDurationMs: 10000,
        coverage: coverage,
        hasReachedEnd: true,
        updatedAt: Date.now()
      })
    );

    // Polling for Cloud Function update
    let unlocked = false;
    for (let i = 0; i < 20; i++) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      const snapshot = await engagementRef.get();
      if (snapshot && snapshot.exists) {
        const data = snapshot.data();
        if (data && data.isCommentUnlocked === true) {
          unlocked = true;
          break;
        }
      }
    }

    if (!unlocked) {
        throw new Error("Cloud Function did not unlock comments within polling period");
    }
  });
});
