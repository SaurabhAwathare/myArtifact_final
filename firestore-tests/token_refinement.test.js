const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

describe("Token Refinement Investigation", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: "myartifact-555e3",
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: fs.readFileSync("token_refinement.rules", "utf8"),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  it("Test context with { appCheck: 'valid-token' }", async () => {
    const context = testEnv.authenticatedContext("user1", { appCheck: "valid-token" });

    const results = {};
    results.appCheck = await context.firestore().collection("appCheck").doc("1").get()
        .then(() => "TRUE").catch(() => "FALSE");
    results.appcheck = await context.firestore().collection("appcheck").doc("1").get()
        .then(() => "TRUE").catch(() => "FALSE");
    results.firebase_app_id = await context.firestore().collection("firebase_app_id").doc("1").get()
        .then(() => "TRUE").catch(() => "FALSE");

    console.log("Results for context { appCheck: 'valid-token' }:", results);
  });

  it("Test context with { appcheck: 'valid-token' }", async () => {
    const context2 = testEnv.authenticatedContext("user1", { appcheck: "valid-token" });
    const results2 = {};
    results2.appCheck = await context2.firestore().collection("appCheck").doc("1").get()
        .then(() => "TRUE").catch(() => "FALSE");
    results2.appcheck = await context2.firestore().collection("appcheck").doc("1").get()
        .then(() => "TRUE").catch(() => "FALSE");
    results2.firebase_app_id = await context2.firestore().collection("firebase_app_id").doc("1").get()
        .then(() => "TRUE").catch(() => "FALSE");

    console.log("Results for context { appcheck: 'valid-token' }:", results2);
  });
});
