import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Improved Mocking for DocumentReference
const mockDoc: any = {
  get: jest.fn(),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
};

// Improved Mocking for CollectionReference
const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(),
};

mockDoc.collection.mockReturnValue(mockCollection);

// Global Firestore Mock
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
      Timestamp: {
        now: () => ({ toMillis: () => Date.now() }),
      }
    }),
  };
});

describe("Report Lifecycle (v2) - Incremental", () => {
  let db: any;

  beforeAll(() => {
    db = admin.firestore();
  });

  beforeEach(() => {
    jest.clearAllMocks();
    // Default document exist state
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

    it("should increment reportCount and establish private marker on CREATE", async () => {
      const wrapped = testEnv.wrap(myFunctions.onReportWrite);

      const after = {
        data: () => ({
          artifactId,
          reporterId,
          reason: "HARASSMENT",
          createdAt: { toMillis: () => 5000 }
        }),
        exists: true
      };

      const change = {
        before: { exists: false, data: () => null } as any,
        after: after as any
      };

      // Mock Transaction
      const transaction = {
        get: jest.fn((ref: any) => {
          if (ref === mockDoc) {
            // Mock artifact doc read
            return Promise.resolve({ exists: true, data: () => ({ reportCount: 5, recommendationState: "ACTIVE" }) });
          }
          return Promise.resolve({ exists: false }); // Idempotency check
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      (db.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(change as any, {
        params: { reportId },
        eventId: "event_create_1",
      });

      // Verify Idempotency check happened
      expect(transaction.get).toHaveBeenCalled();

      // Verify Artifact count incremented (5 + 1 = 6)
      expect(transaction.update).toHaveBeenCalledWith(mockDoc, expect.objectContaining({
        reportCount: 6
      }));

      // Verify Marker established
      expect(transaction.set).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
        reason: "HARASSMENT"
      }));
    });

    it("should decrement reportCount and remove private marker on DELETE", async () => {
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

      const transaction = {
        get: jest.fn((ref: any) => {
          if (ref === mockDoc) {
            return Promise.resolve({ exists: true, data: () => ({ reportCount: 1, recommendationState: "ACTIVE" }) });
          }
          return Promise.resolve({ exists: false });
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      (db.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(change as any, {
        params: { reportId },
        eventId: "event_delete_1",
      });

      // Verify Decrement (1 - 1 = 0)
      expect(transaction.update).toHaveBeenCalledWith(mockDoc, expect.objectContaining({
        reportCount: 0
      }));

      // Verify Marker deleted
      expect(transaction.delete).toHaveBeenCalled();
    });

    it("should apply CHILD_SAFETY override regardless of count and atomically create admin_alert", async () => {
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

      const artifactRefMock = { path: `artifacts/${artifactId}` };

      const transaction = {
        get: jest.fn((ref: any) => {
          if (ref === artifactRefMock) {
            return Promise.resolve({ exists: true, data: () => ({ reportCount: 0, recommendationState: "ACTIVE" }) });
          }
          return Promise.resolve({ exists: false }); // idempotencyRef and alertRef return exists: false
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };

      // Mock db.collection("artifacts").doc(artifactId) to return artifactRefMock
      const originalDoc = mockCollection.doc;
      mockCollection.doc.mockImplementation((path: string) => {
        if (path === artifactId) return artifactRefMock;
        return mockDoc;
      });

      (db.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(change as any, { params: { reportId }, eventId: "event_cs_1" });

      mockCollection.doc = originalDoc;

      expect(transaction.update).toHaveBeenCalledWith(artifactRefMock, expect.objectContaining({
        recommendationState: "SUPPRESSED"
      }));

      // Verify atomic admin alert creation
      expect(transaction.set).toHaveBeenCalledWith(
        mockDoc,
        expect.objectContaining({
          alertId: `report_${reportId}`,
          reportId: reportId,
          artifactId: artifactId,
          reason: "CHILD_SAFETY",
          severity: "CRITICAL",
          dispatchState: "PENDING",
          dispatchAttempts: 0,
          acknowledged: false,
        })
      );
    });

    it("should not create admin_alert for non-CHILD_SAFETY reports", async () => {
      const wrapped = testEnv.wrap(myFunctions.onReportWrite);

      const after = {
        data: () => ({
          artifactId,
          reporterId,
          reason: "SPAM"
        }),
        exists: true
      };

      const change = {
        before: { exists: false, data: () => null } as any,
        after: after as any
      };

      const transaction = {
        get: jest.fn((ref: any) => {
          if (ref === mockDoc) {
            return Promise.resolve({ exists: true, data: () => ({ reportCount: 0, recommendationState: "ACTIVE" }) });
          }
          return Promise.resolve({ exists: false });
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      (db.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(change as any, { params: { reportId }, eventId: "event_spam_1" });

      // Should not set any alert document with reason CHILD_SAFETY
      const alertSetCalls = (transaction.set as jest.Mock).mock.calls.filter((call: any) =>
        call[1] && call[1].reason === "CHILD_SAFETY"
      );
      expect(alertSetCalls.length).toBe(0);
    });
  });

  describe("onPrivateFeedbackWrite", () => {
    it("should increment safetyConcernCount and suppress on threshold", async () => {
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

      const transaction = {
        get: jest.fn((ref: any) => {
          if (ref === mockDoc) {
            // Mock current count at 2, adding 1 makes it 3 (threshold)
            return Promise.resolve({ exists: true, data: () => ({ safetyConcernCount: 2, recommendationState: "ACTIVE" }) });
          }
          return Promise.resolve({ exists: false });
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      (db.runTransaction as any).mockImplementation(async (cb: any) => cb(transaction));

      await wrapped(change as any, {
        params: { feedbackId: "user1_art_feedback" },
        eventId: "event_sf_1",
      });

      expect(transaction.update).toHaveBeenCalledWith(mockDoc, expect.objectContaining({
        safetyConcernCount: 3,
        recommendationState: "SUPPRESSED"
      }));
    });
  });
});
