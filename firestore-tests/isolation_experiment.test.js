const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Firestore Rules Isolation Experiment", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-isolation-project",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("isolation_experiment.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Compare Auth-only vs App-Check-enabled rules", async () => {
    // Context with Auth but NO App Check (simulated failure)
    const authOnlyContext = testEnv.authenticatedContext("user123");
    const db = authOnlyContext.firestore();

    console.log("--- Executing Isolated Read (No App Check Requirement) ---");
    try {
        await db.collection("isolation_no_appcheck").doc("user123").collection("resonance_out").doc("target1").get();
        console.log("RESULT (Auth Only): SUCCESS");
    } catch (e) {
        console.log("RESULT (Auth Only): FAILED - " + e.message);
    }

    console.log("--- Executing Control Read (Existing isAuth() Rule) ---");
    try {
        await db.collection("users").doc("user123").collection("resonance_out").doc("target1").get();
        console.log("RESULT (With isAuth): SUCCESS");
    } catch (e) {
        console.log("RESULT (With isAuth): FAILED (Expected if App Check is gating)");
    }
  });
});
