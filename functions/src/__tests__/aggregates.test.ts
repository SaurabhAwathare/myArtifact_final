import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Shared Mocks
const mockDoc: any = {
  get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({}) })),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
  id: "mock_doc_id"
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(() => Promise.resolve({ empty: true, size: 0, docs: [] })),
};

mockDoc.collection.mockReturnValue(mockCollection);

const mockFirestore: any = {
  collection: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({}) })),
  update: jest.fn(() => Promise.resolve({})),
  set: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  batch: jest.fn(() => ({
    set: jest.fn(),
    update: jest.fn(),
    commit: jest.fn(() => Promise.resolve({})),
    delete: jest.fn(),
  })),
  runTransaction: jest.fn(async (cb: any) => {
    return cb({
      get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({}) })),
      set: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
    });
  }),
  recursiveDelete: jest.fn(() => Promise.resolve()),
  collectionGroup: jest.fn(() => mockCollection),
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
      bucket: () => ({
        file: () => ({
          delete: jest.fn(() => Promise.resolve({})),
        }),
      }),
    }),
  };
});

describe("Aggregate Cloud Functions", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  describe("onCommentCreated", () => {
    it("should increment commentCount when a comment is created", async () => {
      const artifactId = "art123";
      const commentId = "com123";
      const wrapped = testEnv.wrap(myFunctions.onCommentCreated);

      const snapshot = {
        data: () => ({ status: "ACTIVE", text: "Hello", creatorId: "userB" }),
        exists: true
      } as any;

      const transaction = {
        get: (jest.fn() as any).mockResolvedValue({ exists: true, data: () => ({ isPublic: true, userId: "userA" }) }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(snapshot, {
        params: { artifactId, commentId },
        eventId: "event123",
      });

      expect(transaction.update).toHaveBeenCalled();
    });
  });

  describe("onPlayCreated", () => {
    it("should increment playCount when a play event is created", async () => {
      const artifactId = "art123";
      const playId = "play_user1_art123_2026-07-26";
      const wrapped = testEnv.wrap(myFunctions.onPlayCreated);

      const snapshot = { data: () => ({ artifactId, userId: "user1" }) } as any;

      const transaction = {
        get: (jest.fn() as any).mockResolvedValue({ exists: false }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(snapshot, {
        params: { playId },
        eventId: "event456",
      });

      expect(transaction.update).toHaveBeenCalled();
    });
  });

  describe("onArtifactCleanupTrigger", () => {
    it("should execute cascading cleanup when status transitions to DELETED", async () => {
      const artifactId = "art123";
      const wrapped = testEnv.wrap(myFunctions.onArtifactCleanupTrigger);

      const beforeSnapshot = { data: () => ({ status: "ACTIVE" }) };
      const afterSnapshot = {
        data: () => ({
          status: "DELETED",
          audioUrl: "url",
          transcriptUrl: "url",
          userId: "user123"
        }),
        ref: {
          delete: jest.fn(() => Promise.resolve()),
          collection: jest.fn().mockReturnThis(),
        }
      } as any;

      (mockFirestore as any).get = jest.fn(() => Promise.resolve({
        exists: true,
        data: () => ({ moderation: { legalHold: false } })
      }));

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId },
        eventId: "cleanup_123",
      });

      expect(mockFirestore.recursiveDelete).toHaveBeenCalled();
      expect(afterSnapshot.ref.delete).toHaveBeenCalled();
    });
  });
});
