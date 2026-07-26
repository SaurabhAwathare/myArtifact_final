import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocking Firebase Admin Firestore
jest.mock("firebase-admin/firestore", () => ({
  FieldValue: {
    increment: (n: number) => ({ increment: n }),
    serverTimestamp: () => ({ timestamp: "now" }),
  },
}));

// Mocking Firebase Admin
jest.mock("firebase-admin", () => {
  const mockFirestore = {
    collection: jest.fn().mockReturnThis(),
    doc: jest.fn().mockReturnThis(),
    update: jest.fn(() => Promise.resolve({})),
    set: jest.fn(() => Promise.resolve({})),
    delete: jest.fn(() => Promise.resolve({})),
    batch: jest.fn(() => ({
      set: jest.fn(),
      update: jest.fn(),
      commit: jest.fn(() => Promise.resolve({})),
      delete: jest.fn(),
    })),
    runTransaction: jest.fn(),
  };
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
        data: () => ({ status: "ACTIVE", text: "Hello" }),
      } as any;

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        const transaction = {
          get: jest.fn(() => Promise.resolve({ exists: false })),
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
      expect(db.doc).toHaveBeenCalledWith(artifactId);
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

      const beforeSnapshot = {
        data: () => ({ status: "ACTIVE" }),
      } as any;
      const afterSnapshot = {
        data: () => ({ status: "DELETED" }),
      } as any;

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        const transaction = {
          get: jest.fn(() => Promise.resolve({ exists: false })),
          set: jest.fn(),
          update: jest.fn(),
          delete: jest.fn(),
        };
        return cb(transaction);
      });

      await wrapped({ before: beforeSnapshot, after: afterSnapshot }, {
        params: { artifactId, commentId },
        eventId: "event_dec_123",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.doc).toHaveBeenCalledWith(artifactId);
      expect(db.update).toHaveBeenCalledWith({
        commentCount: { increment: -1 },
      });
    });

    it("should NOT decrement if status was already DELETED", async () => {
      const artifactId = "art123";
      const commentId = "com123";
      const wrapped = testEnv.wrap(myFunctions.onCommentUpdated);

      const beforeSnapshot = {
        data: () => ({ status: "DELETED" }),
      } as any;
      const afterSnapshot = {
        data: () => ({ status: "DELETED" }),
      } as any;

      await wrapped({ before: beforeSnapshot, after: afterSnapshot }, {
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

      const snapshot = {
        data: () => ({ artifactId, userId: "user1" }),
      } as any;

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        const transaction = {
          get: jest.fn(() => Promise.resolve({ exists: false })),
          set: jest.fn(),
          update: jest.fn(),
          delete: jest.fn(),
        };
        return cb(transaction);
      });

      await wrapped(snapshot, {
        params: { playId },
        eventId: "event456",
      });

      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(db.doc).toHaveBeenCalledWith(artifactId);
      expect(db.update).toHaveBeenCalledWith({
        playCount: { increment: 1 },
      });
    });
  });

  describe("onArtifactCleanupTrigger", () => {
    it("should execute cascading cleanup when status transitions to DELETED", async () => {
      const artifactId = "art123";
      const wrapped = testEnv.wrap(myFunctions.onArtifactCleanupTrigger);

      const beforeSnapshot = {
        data: () => ({ status: "ACTIVE" }),
      } as any;
      const afterSnapshot = {
        data: () => ({
          status: "DELETED",
          audioUrl: "https://firebasestorage.../o/audio%2Ffile.m4a?...",
          transcriptUrl: "https://firebasestorage.../o/transcripts%2Ffile.json?...",
          userId: "user123"
        }),
        ref: {
          delete: jest.fn(() => Promise.resolve()),
          collection: jest.fn().mockReturnThis(),
        }
      } as any;

      // Mock recursiveDelete
      db.recursiveDelete = jest.fn(() => Promise.resolve());

      // Mock query snapshot for deleteQueryBatch
      const mockQuerySnapshot = {
        size: 1,
        docs: [{ ref: { delete: jest.fn() } }]
      };
      db.get = jest.fn(() => Promise.resolve(mockQuerySnapshot));
      db.where = jest.fn().mockReturnThis();
      db.collectionGroup = jest.fn().mockReturnThis();

      await wrapped({ before: beforeSnapshot, after: afterSnapshot }, {
        params: { artifactId },
        eventId: "cleanup_123",
      });

      // Verify some key deletions
      expect(db.recursiveDelete).toHaveBeenCalled();
      expect(afterSnapshot.ref.delete).toHaveBeenCalled();
      expect(db.collection).toHaveBeenCalledWith("artifact_reaction_counts");
      expect(db.doc).toHaveBeenCalledWith(artifactId);
    });
  });
});
