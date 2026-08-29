import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeEach, jest, afterAll } from "@jest/globals";

const testEnv = functionsTest();

// Mocks
const mockDoc: any = {
  get: jest.fn(),
  update: jest.fn(),
  delete: jest.fn(),
  collection: jest.fn(),
  id: "mock_id",
  ref: { id: "mock_id" }
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  orderBy: jest.fn().mockReturnThis(),
  startAfter: jest.fn().mockReturnThis(),
  get: jest.fn(() => Promise.resolve({ empty: true, size: 0, docs: [] })),
  add: jest.fn(() => Promise.resolve({ id: "new_id" })),
};

mockDoc.collection.mockReturnValue(mockCollection);

const mockFirestore: any = {
  collection: jest.fn((name: string) => {
      return mockCollection;
  }),
  collectionGroup: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  runTransaction: jest.fn(async (cb: any) => cb({
    get: jest.fn().mockImplementation(() => Promise.resolve({ exists: true, data: () => ({}) })),
    update: jest.fn(),
    delete: jest.fn(),
  })),
};

jest.mock("firebase-admin", () => {
  return {
    initializeApp: jest.fn(),
    apps: [] as any[],
    firestore: Object.assign(() => mockFirestore, {
      FieldValue: {
        increment: (n: number) => ({ increment: n }),
        serverTimestamp: () => ({ timestamp: "now" }),
        delete: () => ({ type: "delete" }),
      },
    }),
  };
});

// Mock withIdempotency to just run the callback
jest.mock("../util/idempotency", () => ({
  withIdempotency: jest.fn((key, cb: any) => cb()),
}));

describe("onUserIdentityReset Severing", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFirestore.collection.mockReturnValue(mockCollection);
    mockFirestore.collectionGroup.mockReturnValue(mockCollection);
    mockCollection.get.mockResolvedValue({ empty: true, size: 0, docs: [] });
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  it("should NOT sever relationships if severRelationships is false", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ identityMetadata: { identityResetVersion: 1 } }), exists: true } as any,
      after: { data: () => ({ identityMetadata: { identityResetVersion: 2, severRelationships: false } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    // Setup transaction mock to track updates
    const transactionUpdate = jest.fn();
    (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb({
        get: jest.fn().mockImplementation(() => Promise.resolve({ exists: true, data: () => ({}) })),
        update: transactionUpdate,
    }));

    await wrapped(change, { params: { uid } });

    expect(transactionUpdate).toHaveBeenCalled();

    const updateArg = transactionUpdate.mock.calls[0][1] as any;
    expect(updateArg.resonanceInCount).toBeUndefined();
  });

  it("should sever relationships and reset counters if severRelationships is true", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ identityMetadata: { identityResetVersion: 1 } }), exists: true } as any,
      after: { data: () => ({ identityMetadata: { identityResetVersion: 2, severRelationships: true } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    // Setup transaction mock to track updates
    const transactionUpdate = jest.fn();
    (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb({
        get: jest.fn().mockImplementation(() => Promise.resolve({ exists: true, data: () => ({}) })),
        update: transactionUpdate,
    }));

    // Mock scaleResonanceCleanup targets
    mockCollection.get.mockResolvedValue({ empty: true, size: 0, docs: [] });

    await wrapped(change, { params: { uid } });

    // Verify resonance cleanup was triggered
    expect(mockFirestore.collection).toHaveBeenCalledWith("users");

    // Verify transaction update resets counters to 0
    expect(transactionUpdate).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
        resonanceInCount: 0,
        followersCount: 0,
        resonanceOutCount: 0,
        followingCount: 0
    }));

    const updateArg = transactionUpdate.mock.calls[0][1] as any;
    expect(updateArg["identityMetadata.severRelationships"]).toBeDefined();
  });
});
