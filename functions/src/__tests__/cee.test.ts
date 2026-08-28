import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocking Firebase Admin
jest.mock("firebase-admin", () => {
  const firestoreMock = {
    collection: jest.fn().mockReturnThis(),
    doc: jest.fn().mockReturnThis(),
    where: jest.fn().mockReturnThis(),
    limit: jest.fn().mockReturnThis(),
    get: jest.fn(),
    add: jest.fn(() => Promise.resolve({ id: "audit_123" })),
  };

  const bucketMock = {
    file: jest.fn().mockReturnThis(),
    exists: jest.fn(() => Promise.resolve([true])),
    getSignedUrl: jest.fn(() => Promise.resolve(["https://signed-url.com", "2026-08-24T18:00:00Z"])),
  };

  return {
    initializeApp: jest.fn(),
    apps: [] as any[],
    firestore: Object.assign(() => firestoreMock, {
      FieldValue: {
        serverTimestamp: () => ({ timestamp: "now" }),
      },
    }),
    storage: () => ({
      bucket: () => bucketMock,
    }),
  };
});

describe("Contextual Evidence Elevation (CEE)", () => {
  let db: any;

  beforeAll(() => {
    db = admin.firestore();
  });

  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  const wrapped = testEnv.wrap(myFunctions.revealModerationEvidence);

  it("should deny access if caller is not an admin", async () => {
    const adminUid = "malicious_user";
    // Mock admin settings to return isAdmin: false
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ isAdmin: false }) });

    await expect(wrapped({ artifactId: "art1" }, { auth: { uid: adminUid } }))
      .rejects.toThrow(/Unauthorized/);
  });

  it("should deny access if artifact has no legal hold", async () => {
    const adminUid = "admin_user";
    // 1. Admin check (SUCCESS)
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ isAdmin: true }) });
    // 2. Artifact check (No Hold)
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ moderation: { legalHold: false } }) });

    await expect(wrapped({ artifactId: "art1" }, { auth: { uid: adminUid } }))
      .rejects.toThrow(/not under Legal Hold/);
  });

  it("should deny access if no confirmed CHILD_SAFETY report exists", async () => {
    const adminUid = "admin_user";
    // 1. Admin check
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ isAdmin: true }) });
    // 2. Artifact check (SUCCESS)
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ userId: "creator1", moderation: { legalHold: true } }) });
    // 3. Reports check (EMPTY)
    db.get.mockResolvedValueOnce({ empty: true });

    await expect(wrapped({ artifactId: "art1" }, { auth: { uid: adminUid } }))
      .rejects.toThrow(/No confirmed Child Safety violation/);
  });

  it("should reveal evidence and create audit log for valid request", async () => {
    const adminUid = "admin_user";
    const artifactId = "art1";
    const creatorUid = "creator1";

    // 1. Admin check
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ isAdmin: true }) });
    // 2. Artifact check
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ userId: creatorUid, moderation: { legalHold: true } }) });
    // 3. Reports check
    db.get.mockResolvedValueOnce({ empty: false, docs: [{ id: "rep1" }] });
    // 4. Creator identity retrieval
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ email: "creator@example.com" }) });

    const result = await wrapped({ artifactId }, {
      auth: { uid: adminUid },
      rawRequest: { ip: "1.2.3.4" }
    } as any);

    expect(result.creatorEmail).toBe("creator@example.com");
    expect(result.audioUrl).toBe("https://signed-url.com");
    expect(db.add).toHaveBeenCalledWith(expect.objectContaining({
      adminId: adminUid,
      adminIp: "1.2.3.4",
      action: "EVIDENCE_REVEAL",
      evidenceScope: ["EMAIL", "AUDIO"],
      status: "SUCCESS"
    }));
  });

  it("should reflect partial reveal if audio is missing", async () => {
    const adminUid = "admin_user";
    const artifactId = "art1";
    const creatorUid = "creator1";

    const bucketMock = admin.storage().bucket();
    // @ts-ignore
    bucketMock.file().exists.mockResolvedValueOnce([false]);

    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ isAdmin: true }) });
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ userId: creatorUid, moderation: { legalHold: true } }) });
    db.get.mockResolvedValueOnce({ empty: false, docs: [{ id: "rep1" }] });
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ email: "creator@example.com" }) });

    const result = await wrapped({ artifactId }, {
      auth: { uid: adminUid },
      rawRequest: { ip: "1.2.3.4" }
    } as any);

    expect(result.audioStatus).toBe("MISSING");
    expect(db.add).toHaveBeenCalledWith(expect.objectContaining({
      status: "PARTIAL_MISSING_EVIDENCE",
      evidenceScope: ["EMAIL"]
    }));
  });

  it("should fail-closed if audit write fails", async () => {
    const adminUid = "admin_user";
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ isAdmin: true }) });
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ userId: "creator1", moderation: { legalHold: true } }) });
    db.get.mockResolvedValueOnce({ empty: false, docs: [{ id: "rep1" }] });
    db.get.mockResolvedValueOnce({ exists: true, data: () => ({ email: "creator@example.com" }) });

    // Mock audit write failure
    db.add.mockRejectedValueOnce(new Error("Firestore Error"));

    await expect(wrapped({ artifactId: "art1" }, { auth: { uid: adminUid } }))
      .rejects.toThrow(/An internal error occurred/);
  });
});
