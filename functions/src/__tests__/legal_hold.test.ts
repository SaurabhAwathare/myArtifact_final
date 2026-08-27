import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocking Firebase Admin Firestore
jest.mock("firebase-admin/firestore", () => ({
  FieldValue: {
    serverTimestamp: () => ({ timestamp: "now" }),
  },
}));

const mockBulkWriter = {
  update: jest.fn(),
  close: jest.fn(() => Promise.resolve()),
};

const mockBucket = {
  file: jest.fn().mockReturnThis(),
  delete: jest.fn(() => Promise.resolve({})),
  getFiles: jest.fn(() => Promise.resolve([[]])),
};

// Comprehensive Mock for Firebase Admin
jest.mock("firebase-admin", () => {
  const createQueryMock = (docs: any[] = []) => ({
    where: jest.fn().mockReturnThis(),
    limit: jest.fn().mockReturnThis(),
    get: jest.fn(() => Promise.resolve({ empty: docs.length === 0, docs, size: docs.length })),
    doc: jest.fn((id: any) => createDocMock(id as string)),
    delete: jest.fn(() => Promise.resolve({})),
  });

  const createDocMock = (id: string) => ({
    id,
    exists: true,
    data: jest.fn(() => ({})),
    get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({}) })),
    update: jest.fn(() => Promise.resolve()),
    delete: jest.fn(() => Promise.resolve()),
    collection: jest.fn(() => createQueryMock()),
    ref: {
      id,
      delete: jest.fn(() => Promise.resolve()),
      collection: jest.fn(() => createQueryMock()),
    }
  });

  const firestoreMock = {
    collection: jest.fn(() => createQueryMock()),
    collectionGroup: jest.fn(() => createQueryMock()),
    doc: jest.fn((id) => createDocMock(id as string)),
    where: jest.fn().mockReturnThis(),
    get: jest.fn(),
    recursiveDelete: jest.fn(() => Promise.resolve()),
    bulkWriter: jest.fn(() => mockBulkWriter),
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
      bucket: () => mockBucket,
    }),
  };
});

describe("Legal Hold & Evidence Preservation", () => {
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

  describe("onUserDeleted with Legal Hold", () => {
    it("should skip artifacts with legalHold: true during user deletion", async () => {
      const uid = "suspect_user";
      const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

      const mockArtifacts = [
        { id: "art_held", ref: { id: "art_held" }, data: () => ({ status: "ACTIVE", moderation: { legalHold: true } }) },
        { id: "art_normal", ref: { id: "art_normal" }, data: () => ({ status: "ACTIVE", moderation: { legalHold: false } }) }
      ];

      // Use mockImplementationOnce to preserve original behavior for other calls
      (db.collection as jest.Mock).mockImplementationOnce((name: any) => {
        if (name === "artifacts") {
          return {
            where: jest.fn().mockReturnThis(),
            get: jest.fn(() => Promise.resolve({ empty: false, size: 2, docs: mockArtifacts }))
          };
        }
        return (db.collection as any).getMockImplementation()(name);
      });

      await wrapped({ uid } as any);

      // Should only update 1 artifact (art_normal)
      expect(mockBulkWriter.update).toHaveBeenCalledTimes(1);
      expect(mockBulkWriter.update).toHaveBeenCalledWith(
        expect.objectContaining({ id: "art_normal" }),
        expect.objectContaining({ status: "DELETED" })
      );
    });
  });

  describe("onArtifactCleanupTrigger with Legal Hold", () => {
    it("should abort deletion if latest document state has legalHold: true", async () => {
      const artifactId = "held_artifact";
      const wrapped = testEnv.wrap(myFunctions.onArtifactCleanupTrigger);

      const beforeSnapshot = { data: () => ({ status: "ACTIVE" }) };
      const afterSnapshot = {
        id: artifactId,
        data: () => ({
          status: "DELETED",
          moderation: { legalHold: false }, // Snapshot says false
          audioUrl: "https://storage/held.m4a",
          userId: "user1"
        }),
        ref: {
          id: artifactId,
          delete: jest.fn(),
          collection: jest.fn().mockReturnThis(),
        }
      };

      // MOCK FRESH READ: Latest state has legalHold: true
      const latestDocMock = {
        exists: true,
        data: () => ({ moderation: { legalHold: true } })
      };
      (db.collection as any).mockImplementationOnce(() => ({
        doc: jest.fn().mockReturnValue({
          get: (jest.fn() as any).mockResolvedValue(latestDocMock)
        })
      }));

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId },
        eventId: "cleanup_held",
      } as any);

      // Verify no deletions occurred
      expect(mockBucket.file).not.toHaveBeenCalled();
      expect(afterSnapshot.ref.delete).not.toHaveBeenCalled();
    });

    it("should proceed with deletion if legalHold is false in latest state", async () => {
      const artifactId = "normal_artifact";
      const wrapped = testEnv.wrap(myFunctions.onArtifactCleanupTrigger);

      const beforeSnapshot = { data: () => ({ status: "ACTIVE" }) };
      const afterSnapshot = {
        id: artifactId,
        data: () => ({
          status: "DELETED",
          moderation: { legalHold: false },
          audioUrl: "https://storage/normal.m4a",
          userId: "user1"
        }),
        ref: {
          id: artifactId,
          delete: jest.fn(() => Promise.resolve()),
          collection: jest.fn().mockReturnThis(),
        }
      };

      // MOCK FRESH READ: Latest state has legalHold: false
      const latestDocMock = {
        exists: true,
        data: () => ({ moderation: { legalHold: false }, userId: "user1", audioUrl: "https://storage/normal.m4a" })
      };
      (db.collection as any).mockImplementationOnce(() => ({
        doc: jest.fn().mockReturnValue({
          get: (jest.fn() as any).mockResolvedValue(latestDocMock)
        })
      }));

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId },
        eventId: "cleanup_normal",
      } as any);

      // Verify storage deletion was triggered
      expect(mockBucket.file).toHaveBeenCalled();
      expect(afterSnapshot.ref.delete).toHaveBeenCalled();
    });
  });
});
