const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Auth.get() Support", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("auth_get.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Test auth.get('uid')", async () => {
    const context = testEnv.authenticatedContext("user1");
    await assertSucceeds(context.firestore().collection("auth_get").doc("1").get());
  });
});
