const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

describe("Existence Check", () => {
  let testEnv;

  const rules = `
    rules_version = '2';
    service cloud.firestore {
      match /databases/{database}/documents {
        match /debug/{id} {
          // If request.appcheck is undefined, this throws evaluation error
          // If it is null, this returns true
          allow read: if request.appcheck == null;
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

  it("Check if appcheck is null (exists but empty)", async () => {
    const context = testEnv.unauthenticatedContext();
    await assertSucceeds(context.firestore().collection("debug").doc("1").get());
  });
});
