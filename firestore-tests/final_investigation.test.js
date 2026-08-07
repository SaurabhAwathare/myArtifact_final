const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Final Root Cause Investigation", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("../firestore.rules", "utf8"),
      },
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

  it("INVESTIGATE: Read users/{uid} with Auth + AppCheck", async () => {
    const uid = "test-user";

    // Setup document
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.firestore().collection("users").doc(uid).set({
            anonymousId: "anon1",
            anonymousName: "User 1"
        });
    });

    // Case 1: Auth only (Expected Fail because of hasValidAppCheck)
    const authOnly = testEnv.authenticatedContext(uid);
    console.log("Testing Case 1: Auth only...");
    await assertFails(authOnly.firestore().collection("users").doc(uid).get());

    // Case 2: Auth + AppCheck (Expected Succeed)
    // Note: in rules-unit-testing, providing any object to appCheck makes request.appcheck != null
    const authAndAppCheck = testEnv.authenticatedContext(uid, { appCheck: { token: "valid" } });
    console.log("Testing Case 2: Auth + AppCheck...");
    await assertSucceeds(authAndAppCheck.firestore().collection("users").doc(uid).get());
  });

  it("INVESTIGATE: Read users/{uid}/private/settings with Auth + AppCheck", async () => {
    const uid = "test-user";

    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.firestore().collection("users").doc(uid).collection("private").doc("settings").set({
            isAdmin: false
        });
    });

    const context = testEnv.authenticatedContext(uid, { appCheck: { token: "valid" } });
    await assertSucceeds(context.firestore().collection("users").doc(uid).collection("private").doc("settings").get());
  });
});
