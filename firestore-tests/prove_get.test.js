const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Prove request.get() Support", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("prove_get.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Test 1: Read without token (Should return FALSE, NOT CRASH)", async () => {
    const context = testEnv.unauthenticatedContext();
    console.log("Executing read without token...");
    // If request.get('appcheck', null) works, it returns null. null != null is false.
    // Result should be PERMISSION_DENIED (false), not a runtime error.
    await assertFails(context.firestore().collection("prove_get").doc("1").get());
  });

  it("Test 2: Read with token (Should return TRUE)", async () => {
    // Note: providing appCheck object in the context should make request.get('appcheck', ...) return non-null
    const context = testEnv.authenticatedContext("user1", { appCheck: { token: "valid" } });
    console.log("Executing read with simulated App Check token...");
    await assertSucceeds(context.firestore().collection("prove_get").doc("1").get());
  });
});
