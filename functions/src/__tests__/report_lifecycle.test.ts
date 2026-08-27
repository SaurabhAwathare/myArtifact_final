import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Improved Mocking
const mockDoc: any = {
  get: jest.fn(),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(),
};

mockDoc.collection.mockReturnValue(mockCollection);

jest.mock("firebase-admin", () => {
  const mockFirestore = {
    collection: jest.fn(() => mockCollection),
    doc: jest.fn(() => mockDoc),
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
  };
});

describe("Report Lifecycle (v2)", () => {
  let db: any;

  beforeAll(() => {
    db = admin.firestore();
  });

  beforeEach(() => {
    jest.clearAllMocks();
    mockDoc.get.mockImplementation(() => Promise.resolve({ exists: true, data: () => ({}) }));
    mockCollection.get.mockImplementation(() => Promise.resolve({ docs: [], size: 0 }));
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  describe("onReportWrite", () => {
    const artifactId = "art123";
    const reporterId = "user_A";
    const reportId = `${reporterId}_${artifactId}`;

    it("should establish private marker and update aggregates on CREATE", async () => {
      const wrapped = testEnv.wrap(myFunctions.onReportWrite);

      const after = {
        data: () => ({
          artifactId,
          reporterId,
          reason: "HARASSMENT",
        }),
        exists: true
      };

      const change = {
        before: { exists: false, data: () => null } as any,
        after: after as any
      };

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        const transaction = {
          get: jest.fn(() => Promise.resolve({ exists: false })),
          set: jest.fn(),
          update: jest.fn(),
          delete: jest.fn(),
        };
        return cb(transaction);
      });

      // Mock aggregateReports re-scan
      mockCollection.get.mockImplementation(() => Promise.resolve({
        docs: [
          { data: () => ({ reporterId: "user_A", createdAt: { toMillis: () => 5000 } }) }
        ]
      }));

      await wrapped(change as any, {
        params: { reportId },
        eventId: "event_create_1",
      });

      // Verify Private Marker Path
      expect(db.collection).toHaveBeenCalledWith("users");
      expect(mockDoc.set).toHaveBeenCalledWith(expect.objectContaining({
        reason: "HARASSMENT"
      }));

      // Verify Artifact Aggregate Update
      expect(db.collection).toHaveBeenCalledWith("artifacts");
      expect(mockDoc.update).toHaveBeenCalledWith(expect.objectContaining({
        reportCount: 1
      }));
    });

    it("should remove private marker on DELETE", async () => {
      const wrapped = testEnv.wrap(myFunctions.onReportWrite);

      const before = {
        data: () => ({
          artifactId,
          reporterId,
          reason: "HARASSMENT"
        }),
        exists: true
      };

      const change = {
        before: before as any,
        after: { exists: false, data: () => null } as any
      };

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        return cb({ get: jest.fn(() => Promise.resolve({ exists: false })), set: jest.fn(), update: jest.fn(), delete: jest.fn() });
      });

      mockCollection.get.mockImplementation(() => Promise.resolve({ docs: [], size: 0 }));

      await wrapped(change as any, {
        params: { reportId },
        eventId: "event_delete_1",
      });

      expect(mockDoc.delete).toHaveBeenCalled();
      expect(mockDoc.update).toHaveBeenCalledWith(expect.objectContaining({
        reportCount: 0
      }));
    });

    it("should apply CHILD_SAFETY override regardless of count", async () => {
      const wrapped = testEnv.wrap(myFunctions.onReportWrite);

      const after = {
        data: () => ({
          artifactId,
          reporterId,
          reason: "CHILD_SAFETY"
        }),
        exists: true
      };

      const change = {
        before: { exists: false, data: () => null } as any,
        after: after as any
      };

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        return cb({ get: jest.fn(() => Promise.resolve({ exists: false })), set: jest.fn(), update: jest.fn(), delete: jest.fn() });
      });

      mockCollection.get.mockImplementation(() => Promise.resolve({
        docs: [{ data: () => ({ reporterId: "user_A" }) }]
      }));

      await wrapped(change as any, { params: { reportId }, eventId: "event_cs_1" });

      expect(mockDoc.update).toHaveBeenCalledWith(expect.objectContaining({
        recommendationState: "SUPPRESSED"
      }));
    });
  });

  describe("onPrivateFeedbackWrite", () => {
    it("should aggregate safety concerns on write", async () => {
      const artifactId = "art_feedback";
      const wrapped = testEnv.wrap(myFunctions.onPrivateFeedbackWrite);

      const after = {
        data: () => ({
          artifactId,
          type: "SAFETY_CONCERN"
        }),
        exists: true
      };

      const change = {
        before: { exists: false, data: () => null } as any,
        after: after as any
      };

      (db.runTransaction as any).mockImplementation(async (cb: any) => {
        return cb({ get: jest.fn(() => Promise.resolve({ exists: false })), set: jest.fn(), update: jest.fn(), delete: jest.fn() });
      });

      // Mock aggregateSafetyConcerns: 3 concerns
      mockCollection.get.mockImplementation(() => Promise.resolve({ size: 3 }));

      // Artifact fetch for threshold logic
      mockDoc.get.mockImplementation(() => Promise.resolve({
        exists: true,
        data: () => ({ recommendationState: "ACTIVE" })
      }));

      await wrapped(change as any, {
        params: { feedbackId: "user1_art_feedback" },
        eventId: "event_sf_1",
      });

      expect(mockDoc.update).toHaveBeenCalledWith(expect.objectContaining({
        safetyConcernCount: 3,
        recommendationState: "SUPPRESSED"
      }));
    });
  });
});
