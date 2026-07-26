const { initializeTestEnvironment, assertSucceeds } = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Simple Test", () => {
  let testEnv;
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "simple-test",
      firestore: {
        rules: "rules_version = \u00272\u0027; service cloud.firestore { match /databases/{database}/documents { match /{document\u003d**} { allow read, write: if true; } } }"
      }
    });
  });
  after(async () => { await testEnv.cleanup(); });

  beforeEach(async () => { await testEnv.clearFirestore(); });

  it("should work", async () => {
    const alice = testEnv.authenticatedContext("alice");
    await assertSucceeds(alice.firestore().collection("test").add({ foo: "bar" }));
  });
});
