const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

describe("Debug Request Object", () => {
  let testEnv;

  const rules = `
    rules_version = '2';
    service cloud.firestore {
      match /databases/{database}/documents {
        match /debug/{id} {
          // Check for appcheck in request
          allow read: if ("appcheck" in request);

          // Check for appcheck in request.auth.token
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

  it("Check if 'appcheck' key exists in request", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: { token: "valid" } });
    console.log("Testing read (request.appcheck)...");
    // If "appcheck" in request is true, this succeeds.
    await assertSucceeds(context.firestore().collection("debug").doc("1").get());
  });

  it("Check if 'appcheck' key exists in request.auth.token", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: { token: "valid" } });
    console.log("Testing write (request.auth.token.appcheck)...");
    await assertSucceeds(context.firestore().collection("debug").doc("1").set({foo: "bar"}));
  });
});
