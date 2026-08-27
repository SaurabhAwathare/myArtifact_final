import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Improved Mocking for Deletion Tests
const mockBulkWriter = {
  update: jest.fn(),
  close: jest.fn(() => Promise.resolve()),
};

const mockBucket = {
  file: jest.fn().mockReturnThis(),
  delete: jest.fn(() => Promise.resolve({})),
  deleteFiles: jest.fn(() => Promise.resolve()),
  getFiles: jest.fn(() => Promise.resolve([[]])),
};

const mockDoc: any = {
  id: "mock_id",
  exists: true,
  data: jest.fn(() => ({})),
  get: jest.fn(),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
  ref: {
    id: "mock_id",
    delete: jest.fn(() => Promise.resolve()),
    update: jest.fn(() => Promise.resolve()),
    collection: jest.fn().mockReturnThis(),
  }
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(),
  orderBy: jest.fn().mockReturnThis(),
};

mockDoc.collection.mockReturnValue(mockCollection);
mockDoc.get.mockResolvedValue(mockDoc);

const mockFirestore: any = {
  collection: jest.fn(() => mockCollection),
  collectionGroup: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  batch: jest.fn(() => ({
    set: jest.fn(),
    update: jest.fn(),
    commit: jest.fn(() => Promise.resolve({})),
    delete: jest.fn(),
  })),
  runTransaction: jest.fn(async (cb: any) => {
    return cb({
      get: jest.fn(() => Promise.resolve(mockDoc)),
      set: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
    });
  }),
  recursiveDelete: jest.fn(() => Promise.resolve()),
  bulkWriter: jest.fn(() => mockBulkWriter),
  get: jest.fn(),
};

jest.mock("firebase-admin", () => {
  return {
    initializeApp: jest.fn(),
    apps: [] as any[],
    firestore: Object.assign(() => mockFirestore, {
      FieldValue: {
        increment: (n: number) => ({ increment: n }),
        serverTimestamp: () => ({ timestamp: "now" }),
      },
    }),
    storage: () => ({
      bucket: () => mockBucket,
    }),
  };
});

describe("Account Deletion Pipeline", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockDoc.data.mockReturnValue({});
    mockDoc.exists = true;
    mockCollection.get.mockResolvedValue({ docs: [], size: 0, empty: true });
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  describe("onUserDeleted", () => {
    it("should perform comprehensive remote cleanup when a user is deleted", async () => {
      const uid = "user_to_delete";
      const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

      const mockArtifacts = [
        { ref: { id: "art1" }, data: () => ({ status: "ACTIVE" }) },
        { ref: { id: "art2" }, data: () => ({ status: "ACTIVE" }) }
      ];

      mockCollection.get.mockResolvedValueOnce({ empty: false, size: 2, docs: mockArtifacts } as any);
      mockDoc.data.mockReturnValue({ anonymousName: "Alice" });

      await wrapped({ uid } as any);

      expect(mockBucket.getFiles).toHaveBeenCalledWith({ prefix: `backups/${uid}/` });
      expect(mockBulkWriter.update).toHaveBeenCalledTimes(2);
      expect(mockBulkWriter.close).toHaveBeenCalled();
      expect(mockFirestore.recursiveDelete).toHaveBeenCalled();
    });

    it("should anonymize comments during user deletion", async () => {
      const uid = "commenter_uid";
      const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

      mockCollection.get
        .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
        .mockResolvedValueOnce({ empty: false, size: 3, docs: [{}, {}, {}] })
        .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
        .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
        .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
        .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
        .mockResolvedValueOnce({
            empty: false,
            size: 3,
            docs: [{ ref: {}, data: () => ({}) }, { ref: {}, data: () => ({}) }, { ref: {}, data: () => ({}) }]
        });

      await wrapped({ uid } as any);

      expect(mockBulkWriter.update).toHaveBeenCalledWith(expect.anything(), { creatorId: "" });
    });
  });

  describe("onArtifactCleanupTrigger", () => {
    it("should use predictable path fallback if URLs are missing", async () => {
      const artifactId = "orphaned_art";
      const userId = "user123";
      const wrapped = testEnv.wrap(myFunctions.onArtifactCleanupTrigger);

      const beforeSnapshot = { data: () => ({ status: "ACTIVE" }) };
      const afterSnapshot = {
        data: () => ({
          status: "DELETED",
          userId: userId,
        }),
        ref: {
          delete: jest.fn(() => Promise.resolve()),
          collection: jest.fn().mockReturnThis(),
        }
      };

      (mockFirestore as any).get = jest.fn(() => Promise.resolve({
        exists: true,
        data: () => ({ moderation: { legalHold: false }, userId })
      }));

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId },
        eventId: "cleanup_fallback",
      } as any);

      expect(mockBucket.file).toHaveBeenCalledWith(`artifacts/${userId}_${artifactId}.m4a`);
      expect(mockBucket.file).toHaveBeenCalledWith(`transcripts/${userId}_${artifactId}.json`);
    });
  });
});
