const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

describe("Debug Print Request", () => {
  let testEnv;

  const rules = `
    rules_version = '2';
    service cloud.firestore {
      match /databases/{database}/documents {
        match /debug/{id} {
          // Use debug() to log the request object to firestore-debug.log
          allow read: if debug(request).auth != null;
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

  it("Trigger debug log", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid-token" });
    await context.firestore().collection("debug").doc("1").get();
  });
});
