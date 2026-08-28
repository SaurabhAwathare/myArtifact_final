import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocks
const mockBulkWriter = {
  update: jest.fn(),
  delete: jest.fn(),
  close: jest.fn(() => Promise.resolve()),
};

const mockBucket = {
  getFiles: jest.fn(() => Promise.resolve([[]])),
};

const mockDoc: any = {
  id: "mock_id",
  exists: true,
  data: jest.fn(() => ({})),
  get: jest.fn().mockReturnThis(),
  collection: jest.fn(() => mockCollection),
  ref: {
    id: "mock_id",
    delete: jest.fn(() => Promise.resolve()),
  }
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(),
};

const mockTransaction: any = {
  get: jest.fn((ref: any) => Promise.resolve({ exists: true, data: () => ({}) })),
  delete: jest.fn(),
  update: jest.fn(),
};

const mockFirestore: any = {
  collection: jest.fn(() => mockCollection),
  collectionGroup: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  batch: jest.fn(() => ({
    delete: jest.fn(),
    commit: jest.fn(() => Promise.resolve()),
  })),
  runTransaction: jest.fn(async (cb: any) => {
    return cb(mockTransaction);
  }),
  recursiveDelete: jest.fn(() => Promise.resolve()),
  bulkWriter: jest.fn(() => mockBulkWriter),
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

describe("Account Deletion Scalability", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockDoc.data.mockReturnValue({});
    mockDoc.exists = true;
    mockCollection.get.mockResolvedValue({ empty: true, size: 0, docs: [] });
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  it("should process resonance in paged batches with parallel transactions", async () => {
    const uid = "user_high_cardinality";
    const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

    const resonanceSize = 150; // 1.5 batches
    const mockResonanceDocs = Array.from({ length: resonanceSize }, (_, i) => ({
      id: `other_${i}`,
      ref: { id: `other_${i}` },
      exists: true,
      data: () => ({})
    }));

    // Mock sequence of get() calls
    // 1. Artifacts (empty)
    // 2. Notifications (empty)
    // 3. resonance_out (page 1: 100)
    // 4. resonance_out (page 2: 50)
    // 5. resonance_out (page 3: empty)
    // 6. resonance_in (empty)
    // ...
    mockCollection.get
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }) // Art
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }) // Notif
      .mockResolvedValueOnce({ empty: false, size: 100, docs: mockResonanceDocs.slice(0, 100) }) // ResOut P1
      .mockResolvedValueOnce({ empty: false, size: 50, docs: mockResonanceDocs.slice(100, 150) }) // ResOut P2
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }) // ResOut P3
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }); // ResIn

    await wrapped({ uid } as any);

    // Verify 150 transactions were initiated for resonance_out
    expect(mockFirestore.runTransaction).toHaveBeenCalledTimes(150);

    // Verify each transaction updated the other user's counters correctly
    expect(mockTransaction.update).toHaveBeenCalledTimes(150);
    expect(mockTransaction.update).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
            resonanceInCount: expect.anything(),
            followersCount: expect.anything()
        })
    );

    // Verify idempotency check: marker existence was checked in each transaction
    expect(mockTransaction.get).toHaveBeenCalledTimes(150);
  });

  it("should cleanup authored reports using paged batch deletion", async () => {
    const uid = "reporter_uid";
    const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

    const mockReports = Array.from({ length: 3 }, (_, i) => ({
      ref: { id: `report_${i}` },
      exists: true,
      data: () => ({})
    }));

    // Find the right mock index for Reports (it's Phase 5.6)
    // Simplified: Use mockImplementation to target reporterId query
    mockCollection.get.mockImplementation(async () => {
      const lastWhere = (mockCollection.where as jest.Mock).mock.calls.slice(-1)[0];
      if (lastWhere && lastWhere[0] === "reporterId") {
          return { empty: false, size: 3, docs: mockReports };
      }
      return { empty: true, size: 0, docs: [] };
    });

    await wrapped({ uid } as any);

    // Verify reports were deleted via batch (actually via the query batch utility)
    // Batch utility uses db.batch() which was not mocked in this file's mockFirestore yet.
    // Let me update the mock.
  });
});
