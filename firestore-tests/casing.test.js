const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("App Check Casing Investigation", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("casing.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Check which casing is present in request", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: "token" });
    const result = await context.firestore().collection("casing").doc("1").get()
        .then(() => "SUCCESS")
        .catch((e) => e.message);
    console.log("Casing Test Result:", result);
  });
});
