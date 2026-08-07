const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

describe("Debug Auth Token Claims", () => {
  let testEnv;

  const rules = `
    rules_version = '2';
    service cloud.firestore {
      match /databases/{database}/documents {
        match /debug/{id} {
          allow read: if request.auth != null && request.auth.token.my_claim == "exists";
          allow write: if request.auth != null && ("appcheck" in request.auth.token);
        }
      }
    }
  `;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: rules,
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Verify custom claim is passed", async () => {
    const context = testEnv.authenticatedContext("user1", { my_claim: "exists" });
    await assertSucceeds(context.firestore().collection("debug").doc("1").get());
  });

  it("Check if appcheck is in token if passed via options", async () => {
    // Attempt 1: Passing it as a top-level property (standard for v3)
    const context1 = testEnv.authenticatedContext("user1", { appCheck: "valid" });
    console.log("Testing Case 1: appCheck top-level...");
    const result1 = await context1.firestore().collection("debug").doc("1").set({x:1})
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Result 1:", result1);

    // Attempt 2: Passing it inside 'token' (some older versions or specific setups)
    // In v9+ JS SDK / Rules-Unit-Testing v2+, we often use the 'token' field for claims.
    const context2 = testEnv.authenticatedContext("user1", { appcheck: "valid" });
    console.log("Testing Case 2: appcheck top-level...");
    const result2 = await context2.firestore().collection("debug").doc("1").set({x:1})
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Result 2:", result2);
  });
});
