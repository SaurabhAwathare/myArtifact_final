const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

const PROJECT_ID = "myartifact-555e3";
const STORAGE_RULES = fs.readFileSync("../storage.rules", "utf8");

describe("Storage Investigation Tests", () => {
  let testEnv;

  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      storage: {
        rules: STORAGE_RULES,
        host: "127.0.0.1",
        port: 9199,
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearStorage();
  });

  it("Test 1: Authenticated UID matches prefix -> ALLOW", async () => {
    const uid = "RDiXgldwwnfKV2VSUEGWWrEmVfg1";
    const fileName = "RDiXgldwwnfKV2VSUEGWWrEmVfg1_bb88a7eb-1810-4262-a29f-7d7aeb1a6578.json";
    const alice = testEnv.authenticatedContext(uid);
    const storage = alice.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    await assertSucceeds(fileRef.put(Buffer.from("test")));
  });

  it("Test 2: Authenticated UID does NOT match prefix -> DENY", async () => {
    const uid = "AnotherUser";
    const fileName = "RDiXgldwwnfKV2VSUEGWWrEmVfg1_bb88a7eb-1810-4262-a29f-7d7aeb1a6578.json";
    const bob = testEnv.authenticatedContext(uid);
    const storage = bob.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    await assertFails(fileRef.put(Buffer.from("test")));
  });

  it("Test 3: Unauthenticated -> DENY", async () => {
    const fileName = "RDiXgldwwnfKV2VSUEGWWrEmVfg1_bb88a7eb-1810-4262-a29f-7d7aeb1a6578.json";
    const unauth = testEnv.unauthenticatedContext();
    const storage = unauth.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    await assertFails(fileRef.put(Buffer.from("test")));
  });

  it("Test 4: Authenticated UID matches but no underscore -> DENY", async () => {
    const uid = "RDiXgldwwnfKV2VSUEGWWrEmVfg1";
    const fileName = "RDiXgldwwnfKV2VSUEGWWrEmVfg1.json";
    const alice = testEnv.authenticatedContext(uid);
    const storage = alice.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    await assertFails(fileRef.put(Buffer.from("test")));
  });

  it("Test 5: Minimal valid prefix -> ALLOW", async () => {
    const uid = "user123";
    const fileName = "user123_.json";
    const alice = testEnv.authenticatedContext(uid);
    const storage = alice.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    await assertSucceeds(fileRef.put(Buffer.from("test")));
  });

  it("Test 6: Nested path -> DENY (Flat structure enforcement)", async () => {
    const uid = "user123";
    const fileName = "user123_folder/file.json";
    const alice = testEnv.authenticatedContext(uid);
    const storage = alice.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    // This should fail because the rule match is /transcripts/{fileName}
    // and {fileName} matches a single path segment.
    await assertFails(fileRef.put(Buffer.from("test")));
  });

  it("Test 7: Double underscore -> ALLOW", async () => {
    const uid = "user123";
    const fileName = "user123__double.json";
    const alice = testEnv.authenticatedContext(uid);
    const storage = alice.storage();
    const fileRef = storage.ref(`transcripts/${fileName}`);

    await assertSucceeds(fileRef.put(Buffer.from("test")));
  });
});
