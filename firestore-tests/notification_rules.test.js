const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Notification Rules", () => {
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

  it("should allow recipient to update isRead", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("notifications").doc("notif1").set({
        userId: "alice",
        message: "Hello",
        isRead: false,
      });
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(
      alice.firestore().collection("notifications").doc("notif1").update({
        isRead: true,
      })
    );
  });

  it("should prevent recipient from updating other fields", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("notifications").doc("notif1").set({
        userId: "alice",
        message: "Hello",
        isRead: false,
      });
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("notifications").doc("notif1").update({
        message: "Hacked",
      })
    );
  });

  it("should prevent non-recipient from reading notifications", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("notifications").doc("notif1").set({
        userId: "alice",
        message: "Hello",
      });
    });

    const bob = testEnv.authenticatedContext("bob");
    await assertFails(
      bob.firestore().collection("notifications").doc("notif1").get()
    );
  });
});
