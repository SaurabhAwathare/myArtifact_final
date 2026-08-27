import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

const mockFirestore: any = {
  collection: jest.fn(() => mockFirestore),
  collectionGroup: jest.fn(() => mockFirestore),
  where: jest.fn(() => mockFirestore),
  orderBy: jest.fn(() => mockFirestore),
  startAfter: jest.fn(() => mockFirestore),
  limit: jest.fn(() => mockFirestore),
  get: jest.fn(),
  doc: jest.fn((id: string) => ({ id, exists: true, data: () => ({}), ref: { id } })),
  batch: jest.fn(() => ({
    update: jest.fn(),
    commit: jest.fn(() => Promise.resolve({})),
  })),
  runTransaction: jest.fn(async (cb: any) => cb({ get: jest.fn(() => Promise.resolve({ exists: true, data: () => ({}) })), update: jest.fn() })),
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
      Timestamp: { now: () => ({ seconds: 0, nanoseconds: 0, toMillis: () => 0 }) }
    }),
  };
});

describe("onUserIdentityReset", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFirestore.get.mockResolvedValue({ empty: true, size: 0, docs: [] });
  });

  it("should trigger propagation when identityResetVersion increases", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ identityMetadata: { identityResetVersion: 1 } }), exists: true } as any,
      after: { data: () => ({ anonymousName: "New", identityMetadata: { identityResetVersion: 2 } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    mockFirestore.get
      .mockResolvedValueOnce({ empty: false, size: 1, docs: [{ ref: { id: "art1" }, data: () => ({ identityVersion: 0 }) }] })
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
      .mockResolvedValueOnce({ empty: false, size: 1, docs: [{ ref: { id: "com1" }, data: () => ({ identityVersion: 0 }) }] })
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] });

    await wrapped(change, { params: { uid } });

    expect(mockFirestore.collection).toHaveBeenCalledWith("artifacts");
    expect(mockFirestore.collectionGroup).toHaveBeenCalledWith("comments");
  });

  it("should only update documents with OLDER identity version", async () => {
    const uid = "user_123";
    const newVersion = 5;
    const change = {
      before: { data: () => ({ identityMetadata: { identityResetVersion: 4 } }), exists: true } as any,
      after: { data: () => ({ anonymousName: "New", identityMetadata: { identityResetVersion: newVersion } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    const batchUpdateMock = jest.fn();
    mockFirestore.batch.mockReturnValue({
      update: batchUpdateMock,
      commit: jest.fn(() => Promise.resolve({})),
    });

    const docOld = { ref: { id: "ref1" }, data: () => ({ identityVersion: 3 }) };
    const docSame = { ref: { id: "ref2" }, data: () => ({ identityVersion: 5 }) };

    mockFirestore.get
      .mockResolvedValueOnce({ empty: false, size: 2, docs: [docOld, docSame] })
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
      .mockResolvedValue({ empty: true, size: 0, docs: [] });

    await wrapped(change, { params: { uid } });

    expect(batchUpdateMock).toHaveBeenCalled();
    // Check that we updated the old document
    const hasRef1 = batchUpdateMock.mock.calls.some(call => (call[0] as any).id === "ref1");
    expect(hasRef1).toBe(true);

    // Check that we DID NOT update the same-version document
    const hasRef2 = batchUpdateMock.mock.calls.some(call => (call[0] as any).id === "ref2");
    expect(hasRef2).toBe(false);
  });
});
