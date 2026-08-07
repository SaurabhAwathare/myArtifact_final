const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Minimal App Check Reproduction", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("minimal.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Reproduction 1: Check for 'undefined' vs 'null' crash", async () => {
    const context = testEnv.unauthenticatedContext();
    console.log("Running Repro 1 (Checking if request.appcheck == null)...");

    // If request.appcheck is NULL, this succeeds.
    // If request.appcheck is UNDEFINED, this throws a terminal evaluation error.
    try {
        await context.firestore().collection("existence_check").doc("1").get();
        console.log("RESULT: Success (request.appcheck exists and is null)");
    } catch (e) {
        console.log("RESULT: Error (request.appcheck is likely undefined or rules evaluation crashed)");
        console.log("Error Message:", e.message);
    }
  });

  it("Reproduction 2: Read with simulated App Check token", async () => {
    // In rules-unit-testing, passing an object as the second arg to authenticatedContext
    // with an 'appCheck' property should populate request.appcheck.
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid-token" });
    console.log("Running Repro 2 (Checking if request.appcheck != null with token)...");

    try {
        await context.firestore().collection("test").doc("1").get();
        console.log("RESULT: Success (App Check token correctly identified)");
    } catch (e) {
        console.log("RESULT: Error (Failed to recognize App Check token)");
        console.log("Error Message:", e.message);
    }
  });
});
