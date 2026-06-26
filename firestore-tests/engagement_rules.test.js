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

  it("should unlock comments using authoritative duration from artifact", async function() {
    this.timeout(30000);

    const admin = testEnv.unauthenticatedContext().firestore();
    const artifactRef = admin.collection("artifacts").doc("art1");

    // Set authoritative duration to 10s
    await artifactRef.set({
      userId: "bob",
      durationMs: 10000,
      isPublic: true,
      status: "ACTIVE",
      createdAt: new Date()
    });

    const alice = testEnv.authenticatedContext("alice");
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art1");

    // 100% coverage bitset for a 10s audio
    const coverage = [255, 255, 15];

    await assertSucceeds(
      engagementRef.set({
        artifactId: "art1",
        userId: "alice",
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
        throw new Error("Cloud Function did not unlock comments using authoritative duration");
    }
  });

  it("should NOT unlock if client lies about duration", async function() {
    this.timeout(30000);

    const admin = testEnv.unauthenticatedContext().firestore();
    const artifactRef = admin.collection("artifacts").doc("art_long");

    // Set authoritative duration to 100s
    await artifactRef.set({
      userId: "bob",
      durationMs: 100000,
      isPublic: true,
      status: "ACTIVE",
      createdAt: new Date()
    });

    const alice = testEnv.authenticatedContext("alice");
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art_long");

    // Client sends 10s worth of coverage for a 100s artifact
    const coverage = [255, 255, 15];

    await assertSucceeds(
      engagementRef.set({
        artifactId: "art_long",
        userId: "alice",
        totalDurationMs: 10000, // Lie
        coverage: coverage,
        hasReachedEnd: true,
        updatedAt: Date.now()
      })
    );

    // Wait a bit and check
    await new Promise(resolve => setTimeout(resolve, 5000));
    const snapshot = await engagementRef.get();
    const data = snapshot.data();

    if (data && data.isCommentUnlocked === true) {
        throw new Error("Cloud Function unlocked even though client lied about duration");
    }
  });

  it("should aggregate coverage across devices (multi-device)", async function() {
    this.timeout(30000);

    const admin = testEnv.unauthenticatedContext().firestore();
    const artifactRef = admin.collection("artifacts").doc("art_multi");
    await artifactRef.set({ userId: "bob", durationMs: 10000, isPublic: true, status: "ACTIVE", createdAt: new Date() });

    const alice = testEnv.authenticatedContext("alice");
    const engagementRef = alice.firestore().collection("users").doc("alice").collection("engagement").doc("art_multi");

    // Device A: First 5s (Segments 0-9) -> Byte 0: 255, Byte 1: 3
    await engagementRef.set({
        artifactId: "art_multi",
        userId: "alice",
        coverage: [255, 3],
        hasReachedEnd: false,
        updatedAt: Date.now()
    });

    // Wait for first processing
    await new Promise(resolve => setTimeout(resolve, 3000));

    // Device B: Next 5s (Segments 10-19) -> Byte 1: 252 (bits 10-15), Byte 2: 15 (bits 16-19)
    await engagementRef.update({
        coverage: [0, 252, 15],
        hasReachedEnd: true,
        updatedAt: Date.now() + 1000
    });

    // Polling for unlock
    let unlocked = false;
    for (let i = 0; i < 15; i++) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      const snapshot = await engagementRef.get();
      if (snapshot?.exists) {
        const data = snapshot.data();
        if (data?.isCommentUnlocked === true) {
          unlocked = true;
          break;
        }
      }
    }

    if (!unlocked) {
        throw new Error("Multi-device coverage aggregation failed to unlock");
    }
  });
});
