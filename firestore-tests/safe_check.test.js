const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

describe("Safe Property Check", () => {
  let testEnv;

  const rules = `
    rules_version = '2';
    service cloud.firestore {
      match /databases/{database}/documents {
        match /debug/{id} {
          // Use .get() to safely check for a potentially missing property
          allow read: if request.get('appcheck', null) != null;
          allow write: if request.auth != null;
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

  it("Verify that .get('appcheck', null) avoids the undefined error", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid" });
    // This should NOT throw "undefined on object" error.
    // It will return false if appcheck is missing or null.
    console.log("Testing read with .get('appcheck')...");
    const result = await context.firestore().collection("debug").doc("1").get()
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Result:", result);
    // If it says "PERMISSION_DENIED" instead of "Property appcheck is undefined", we've made progress.
  });
});
