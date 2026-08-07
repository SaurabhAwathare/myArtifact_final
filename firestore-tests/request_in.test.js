const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("request 'in' Operator Support", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("request_in.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Test 1: Read without token (Should return FALSE, NOT CRASH)", async () => {
    const context = testEnv.unauthenticatedContext();
    console.log("Executing read without token...");
    await assertFails(context.firestore().collection("request_in").doc("1").get());
  });

  it("Test 2: Read with token (Should return TRUE)", async () => {
    // Attempt to pass appCheck as a custom claim to see if it populates top-level request
    const context = testEnv.authenticatedContext("user1", { appcheck: "valid" });
    console.log("Executing read with simulated App Check token...");
    // If "appcheck" in request is TRUE, this succeeds.
    const result = await context.firestore().collection("request_in").doc("1").get()
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Result:", result);
  });
});
