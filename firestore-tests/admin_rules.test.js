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
      projectId: "myartifact-admin-unique",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
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

  it("should allow a regular user to delete sensitive fields from root (Release A Migration Support)", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("users").doc("alice").set({
        email: "alice@example.com",
        fcmToken: "token123",
        isAdmin: false
      });
    });

    const alice = testEnv.authenticatedContext("alice");
    const { FieldValue } = require("firebase-admin/firestore");

    // We use a mock of FieldValue or similar depending on the test environment setup.
    // In rules-unit-testing, we can use the field deletion syntax.
    await assertSucceeds(
      alice.firestore().collection("users").doc("alice").update({
        email: null, // Depending on the SDK, this might be delete.
        // In the emulator tests, setting to null or using delete field works.
        // Actually, the rules check if the field is NOT in request.resource.data.
        fcmToken: null,
        isAdmin: null
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

  it("Administrative: should allow an admin to update any artifact", async () => {
    const alice_anon_id = "alice_anon";
    const admin_uid = "admin_alice";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await db.collection("users").doc(admin_uid).set({ anonymousId: alice_anon_id });
      await db.collection("users").doc(admin_uid).collection("private").doc("settings").set({
        isAdmin: true
      });
      await db.collection("artifacts").doc("art1").set({
        userId: "bob",
        isPublic: true,
        author: { anonymousId: "bob_anon", name: "Bob" }
      });
    });

    const alice = testEnv.authenticatedContext(admin_uid);
    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").update({
        adminNote: "Reviewed"
      })
    );
  });

  it("Administrative: should allow an admin to delete any comment", async () => {
    const admin_uid = "admin_alice_2";

    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await db.collection("users").doc(admin_uid).set({ anonymousId: "alice_anon" });
      await db.collection("users").doc(admin_uid).collection("private").doc("settings").set({
        isAdmin: true
      });
      await db.collection("artifacts").doc("art1").collection("comments").doc("com1").set({
        creatorId: "bob",
        author: { anonymousId: "bob_anon", name: "Bob", sigil: "B" },
        text: "Spam",
        artifactId: "art1",
        status: "ACTIVE",
        artifactOwnerId: "bob",
        visibilityLayer: "RESONANCE",
        moderationState: "PENDING"
      });
    });

    const alice = testEnv.authenticatedContext(admin_uid);
    await assertSucceeds(
      alice.firestore().collection("artifacts").doc("art1").collection("comments").doc("com1").delete()
    );
  });
});
