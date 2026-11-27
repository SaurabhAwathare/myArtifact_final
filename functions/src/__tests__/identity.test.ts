import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeEach, jest, afterAll } from "@jest/globals";

const testEnv = functionsTest();

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
  collection: jest.fn(() => mockCollection),
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

describe("onUserIdentityReset", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFirestore.collection.mockReturnValue(mockCollection);
    mockFirestore.collectionGroup.mockReturnValue(mockCollection);
    mockCollection.get.mockResolvedValue({ empty: true, size: 0, docs: [] });
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  it("should trigger propagation and update lastCompletedIdentityVersion when identityResetVersion increases", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ anonymousId: "usr_123", identityMetadata: { identityResetVersion: 1 } }), exists: true } as any,
      after: { data: () => ({ anonymousId: "usr_123", anonymousName: "New", identityMetadata: { identityResetVersion: 2 } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    const transactionUpdate = jest.fn();
    (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb({
      get: jest.fn().mockImplementation(() => Promise.resolve({
        exists: true,
        data: () => ({ identityMetadata: { lastCompletedIdentityVersion: 1 } })
      })),
      update: transactionUpdate,
    }));

    await wrapped(change, { params: { uid } });

    expect(transactionUpdate).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        "identityMetadata.lastCompletedIdentityVersion": 2,
        "identityMetadata.resetCompletedAt": expect.anything()
      })
    );
  });

  it("should ignore update when identityResetVersion is unchanged or decreases", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ identityMetadata: { identityResetVersion: 2 } }), exists: true } as any,
      after: { data: () => ({ identityMetadata: { identityResetVersion: 2 } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    const transactionUpdate = jest.fn();
    (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb({
      get: jest.fn().mockImplementation(() => Promise.resolve({ exists: true, data: () => ({}) })),
      update: transactionUpdate,
    }));

    const result = await wrapped(change, { params: { uid } });

    expect(result).toBeNull();
    expect(mockFirestore.runTransaction).not.toHaveBeenCalled();
  });

  it("should ensure monotonic lastCompletedIdentityVersion using Math.max", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ identityMetadata: { identityResetVersion: 2 } }), exists: true } as any,
      after: { data: () => ({ identityMetadata: { identityResetVersion: 3 } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    const transactionUpdate = jest.fn();
    (mockFirestore.runTransaction as any).mockImplementation(async (cb: any) => cb({
      get: jest.fn().mockImplementation(() => Promise.resolve({
        exists: true,
        data: () => ({ identityMetadata: { lastCompletedIdentityVersion: 5 } }) // higher than newVersion 3
      })),
      update: transactionUpdate,
    }));

    await wrapped(change, { params: { uid } });

    expect(transactionUpdate).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        "identityMetadata.lastCompletedIdentityVersion": 5
      })
    );
  });
});
