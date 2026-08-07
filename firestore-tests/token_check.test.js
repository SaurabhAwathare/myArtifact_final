const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("App Check Token Claims Investigation", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("token_check.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Check if App Check info is inside request.auth.token", async () => {
    // We try to pass it in different ways to the context
    console.log("Testing context with appCheck...");
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid-token" });
    const result = await context.firestore().collection("token_check").doc("1").get()
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Token Test Result (appCheck top-level):", result);

    console.log("Testing context with appcheck inside token...");
    // Pass it as a custom claim
    const context2 = testEnv.authenticatedContext("user1", { appcheck: "valid-token" });
    const result2 = await context2.firestore().collection("token_check").doc("1").get()
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Token Test Result (appcheck inside token):", result2);
  });
});
