import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Improved Mocking
const mockDoc: any = {
  get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({}) })),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(() => Promise.resolve({ docs: [], size: 0 })),
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
      get: jest.fn(() => Promise.resolve({ exists: false, data: () => ({}) })),
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

  describe("onCommentCreated", () => {
    it("should increment commentCount when a comment is created", async () => {
      const artifactId = "art123";
      const commentId = "com123";
      const wrapped = testEnv.wrap(myFunctions.onCommentCreated);

      const snapshot = {
        data: () => ({ status: "ACTIVE", text: "Hello", creatorId: "userB" }),
        exists: true
      } as any;

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        const transaction = {
          get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({ isPublic: true, userId: "userA" }) })),
          set: jest.fn(),
          update: jest.fn(),
          delete: jest.fn(),
        };
        return cb(transaction);
      });

      await wrapped(snapshot, {
        params: { artifactId, commentId },
        eventId: "event123",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.update).toHaveBeenCalledWith({
        commentCount: { increment: 1 },
      });
    });
  });

  describe("onCommentUpdated", () => {
    it("should decrement commentCount when a comment is soft-deleted", async () => {
      const artifactId = "art123";
      const commentId = "com123";
      const wrapped = testEnv.wrap(myFunctions.onCommentUpdated);

      const beforeSnapshot = { data: () => ({ status: "ACTIVE" }) } as any;
      const afterSnapshot = { data: () => ({ status: "DELETED" }) } as any;

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId, commentId },
        eventId: "event_dec_123",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.update).toHaveBeenCalledWith({
        commentCount: { increment: -1 },
      });
    });

    it("should NOT decrement if status was already DELETED", async () => {
      const artifactId = "art123";
      const commentId = "com123";
      const wrapped = testEnv.wrap(myFunctions.onCommentUpdated);

      const beforeSnapshot = { data: () => ({ status: "DELETED" }) } as any;
      const afterSnapshot = { data: () => ({ status: "DELETED" }) } as any;

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId, commentId },
        eventId: "event_dec_456",
      });

      expect(db.update).not.toHaveBeenCalled();
    });
  });

  describe("onPlayCreated", () => {
    it("should increment playCount when a play event is created", async () => {
      const artifactId = "art123";
      const playId = "play_user1_art123_2026-07-26";
      const wrapped = testEnv.wrap(myFunctions.onPlayCreated);

      const snapshot = { data: () => ({ artifactId, userId: "user1" }) } as any;

      await wrapped(snapshot, {
        params: { playId },
        eventId: "event456",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.update).toHaveBeenCalledWith({
        playCount: { increment: 1 },
      });
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

      db.get.mockResolvedValue({
        exists: true,
        data: () => ({ moderation: { legalHold: false } })
      });

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId },
        eventId: "cleanup_123",
      });

      expect(db.recursiveDelete).toHaveBeenCalled();
      expect(afterSnapshot.ref.delete).toHaveBeenCalled();
    });
  });

  describe("onPrivateFeedbackWrite", () => {
    it("should aggregate safety concerns on write", async () => {
      const artifactId = "art123";
      const feedbackId = "feed123";
      const wrapped = testEnv.wrap(myFunctions.onPrivateFeedbackWrite);

      const snapshot = {
        data: () => ({ artifactId, type: "SAFETY_CONCERN" }),
        exists: true
      } as any;

      const change = { before: { exists: false, data: () => null }, after: snapshot };

      // Mock aggregateSafetyConcerns: 3 concerns
      mockCollection.get.mockResolvedValue({ size: 3 });
      db.get.mockResolvedValue({ exists: true, data: () => ({ recommendationState: "ACTIVE" }) });

      await wrapped(change as any, {
        params: { feedbackId },
        eventId: "event_sf_123",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.update).toHaveBeenCalledWith(expect.objectContaining({
        safetyConcernCount: 3,
        recommendationState: "SUPPRESSED"
      }));
    });
  });

  describe("onReportWrite", () => {
    it("should recalculate reportCount on report write", async () => {
      const artifactId = "art123";
      const reportId = "rep123";
      const wrapped = testEnv.wrap(myFunctions.onReportWrite);

      const snapshot = {
        data: () => ({ artifactId, reporterId: "user1", reason: "OTHER" }),
        exists: true
      } as any;

      const change = { before: { exists: false, data: () => null }, after: snapshot };

      // Mock aggregateReports behavior
      mockCollection.get.mockResolvedValue({
        docs: [{ data: () => ({ reporterId: "user2", createdAt: { toMillis: () => 1000 } }) }]
      });

      await wrapped(change as any, {
        params: { reportId },
        eventId: "event_rep_write_123",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.update).toHaveBeenCalledWith(expect.objectContaining({
        reportCount: 1
      }));
    });
  });
});
