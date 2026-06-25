const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

let testEnv;

describe("Admin Privilege Escalation", () => {
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

  it("should prevent a regular user from making themselves an admin", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").set({
        isAdmin: true,
      })
    );
  });

  it("should prevent a regular user from updating their isAdmin status", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc("alice").set({
        isAdmin: false,
      });
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").update({
        isAdmin: true,
      })
    );
  });

  it("should prevent a regular user from updating their accountStatus", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc("alice").set({
        accountStatus: "ACTIVE",
      });
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").update({
        accountStatus: "BANNED",
      })
    );
  });

  it("should prevent a regular user from making themselves an admin in private settings", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").collection("private").doc("settings").set({
        isAdmin: true,
      })
    );
  });

  it("should prevent a regular user from updating isAdmin in private settings", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc("alice").collection("private").doc("settings").set({
        isAdmin: false,
      });
    });

    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").collection("private").doc("settings").update({
        isAdmin: true,
      })
    );
  });

  it("should prevent a regular user from making themselves an admin using 'admin' field", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertFails(
      alice.firestore().collection("users").doc("alice").set({
        admin: true,
      })
    );
  });
});
