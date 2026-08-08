const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("App Check Context Experiment - Final", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-appcheck-experiment",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("appcheck_experiment.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Final Verification of all conditions", async () => {
    // 1. Context with App Check simulated via Auth Token overrides
    const authWithAppCheck = testEnv.authenticatedContext("user1", {
        appCheck: "some-token",
        firebase: { app_id: "1:1234567890:android:abcdef" }
    });
    const db = authWithAppCheck.firestore();

    console.log("--- Results with simulated App Check in Token ---");

    try {
        await db.collection("test_in_appCheck").doc("1").get();
        console.log("1. ('appCheck' in request): TRUE");
    } catch (e) {
        console.log("1. ('appCheck' in request): FALSE");
    }

    try {
        await db.collection("test_appCheck").doc("1").get();
        console.log("2. (request.appCheck != null): TRUE");
    } catch (e) {
        console.log("2. (request.appCheck != null): ERROR/FALSE - " + (e.message.includes("undefined") ? "CRASHED" : "FALSE"));
        if (e.message.includes("undefined")) console.log("   Actual Error: " + e.message);
    }

    try {
        await db.collection("test_hasValidAppCheck").doc("1").get();
        console.log("3. (hasValidAppCheck()): TRUE");
    } catch (e) {
        console.log("3. (hasValidAppCheck()): FALSE");
    }
  });
});
