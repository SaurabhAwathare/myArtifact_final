const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Identity Reset Rules", () => {
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

  it("should allow monotonic increment of identityResetVersion", async () => {
    await setupUser("alice", {
      identityMetadata: {
        identityResetVersion: 1,
        lastCompletedIdentityVersion: 1,
      },
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("users").doc("alice").update({
        "identityMetadata.identityResetVersion": 2,
      })
    );
  });

  it("should prevent decreasing identityResetVersion", async () => {
    await setupUser("alice", {
      identityMetadata: {
        identityResetVersion: 2,
        lastCompletedIdentityVersion: 1,
      },
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").update({
        "identityMetadata.identityResetVersion": 1,
      })
    );
  });

  it("should prevent lastCompletedIdentityVersion from exceeding identityResetVersion", async () => {
    await setupUser("alice", {
      identityMetadata: {
        identityResetVersion: 2,
        lastCompletedIdentityVersion: 2,
      },
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").update({
        "identityMetadata.lastCompletedIdentityVersion": 3,
      })
    );
  });

  it("should allow updating both versions correctly", async () => {
    await setupUser("alice", {
      identityMetadata: {
        identityResetVersion: 1,
        lastCompletedIdentityVersion: 1,
      },
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("users").doc("alice").update({
        "identityMetadata.identityResetVersion": 2,
        "identityMetadata.lastCompletedIdentityVersion": 2,
      })
    );
  });

  it("should prevent decreasing emergencyResetCount", async () => {
    await setupUser("alice", {
      identityMetadata: {
        emergencyResetCount: 5,
      },
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").update({
        "identityMetadata.emergencyResetCount": 4,
      })
    );
  });
});
