const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

describe("Debug Request Object Case Sensitivity", () => {
  let testEnv;

  const rules = `
    rules_version = '2';
    service cloud.firestore {
      match /databases/{database}/documents {
        match /debug/{id} {
          allow read: if ("appcheck" in request) || ("appCheck" in request) || ("AppCheck" in request);
          allow write: if request.auth != null && (("appcheck" in request.auth.token) || ("appCheck" in request.auth.token));
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

  it("Check for any casing of appcheck", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid-token" });
    console.log("Testing with appCheck string...");
    await assertSucceeds(context.firestore().collection("debug").doc("1").get());
  });

  it("Check for any casing of appcheck with object", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: { token: "valid" } });
    console.log("Testing with appCheck object...");
    await assertSucceeds(context.firestore().collection("debug").doc("1").get());
  });
});
