const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Diagnostic Evaluation", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("diagnostic_eval.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Evaluate with simulated App Check (CamelCase)", async () => {
    console.log("Evaluating with simulated App Check (CamelCase)...");
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid-token" });
    await context.firestore().collection("diagnostic").doc("1").get();
  });

  it("Evaluate with simulated appcheck (lowercase)", async () => {
    console.log("Evaluating with simulated appcheck (lowercase)...");
    const context = testEnv.authenticatedContext("user1", { appcheck: "valid-token" });
    await context.firestore().collection("diagnostic").doc("1").get();
  });

  it("Evaluate without App Check", async () => {
    console.log("Evaluating without App Check...");
    const context = testEnv.authenticatedContext("user1");
    await context.firestore().collection("diagnostic").doc("1").get();
  });
});
