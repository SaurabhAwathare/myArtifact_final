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
    mockFirestore.collection.mockReturnValue(mockFirestore);
    mockFirestore.collectionGroup.mockReturnValue(mockFirestore);
    mockFirestore.where.mockReturnValue(mockFirestore);
    mockFirestore.orderBy.mockReturnValue(mockFirestore);
    mockFirestore.startAfter.mockReturnValue(mockFirestore);
    mockFirestore.limit.mockReturnValue(mockFirestore);
    mockFirestore.get.mockReset();
    mockFirestore.get.mockResolvedValue({ empty: true, size: 0, docs: [] });
  });

  it("should trigger propagation when identityResetVersion increases", async () => {
    const uid = "user_123";
    const change = {
      before: { data: () => ({ anonymousId: "usr_123", identityMetadata: { identityResetVersion: 1 } }), exists: true } as any,
      after: { data: () => ({ anonymousId: "usr_123", anonymousName: "New", identityMetadata: { identityResetVersion: 2 } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    mockFirestore.get
      .mockResolvedValueOnce({ empty: false, size: 1, docs: [{ ref: { id: "art1" }, data: () => ({ identityPropagationVersion: 0, identityVersion: 0 }) }] })
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
      .mockResolvedValueOnce({ empty: false, size: 1, docs: [{ ref: { id: "com1" }, data: () => ({ identityPropagationVersion: 0, identityVersion: 0 }) }] })
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] });

    await wrapped(change, { params: { uid } });

    expect(mockFirestore.collection).toHaveBeenCalledWith("artifacts");
    expect(mockFirestore.collectionGroup).toHaveBeenCalledWith("comments");
  });

  it("should only update documents with OLDER identity propagation version and preserve identityVersion", async () => {
    const uid = "user_123";
    const newVersion = 5;
    const change = {
      before: { data: () => ({ anonymousId: "usr_123", identityMetadata: { identityResetVersion: 4 } }), exists: true } as any,
      after: { data: () => ({ anonymousId: "usr_123", anonymousName: "New", identityMetadata: { identityResetVersion: newVersion } }), exists: true } as any
    };

    const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

    const batchUpdateMock = jest.fn();
    mockFirestore.batch.mockReturnValue({
      update: batchUpdateMock,
      commit: jest.fn(() => Promise.resolve({})),
    });

    const docOld = { ref: { id: "ref1" }, data: () => ({ identityPropagationVersion: 3, identityVersion: 1 }) };
    const docSame = { ref: { id: "ref2" }, data: () => ({ identityPropagationVersion: 5, identityVersion: 1 }) };
    const docLegacy = { ref: { id: "ref3" }, data: () => ({ identityVersion: 1 }) }; // missing identityPropagationVersion

    mockFirestore.get
      .mockResolvedValueOnce({ empty: false, size: 3, docs: [docOld, docSame, docLegacy] })
      .mockResolvedValueOnce({ empty: true, size: 0, docs: [] })
      .mockResolvedValue({ empty: true, size: 0, docs: [] });

    await wrapped(change, { params: { uid } });

    expect(batchUpdateMock).toHaveBeenCalled();

    // Check ref1 update payload: must set identityPropagationVersion = 5, must NOT set identityVersion
    const ref1Call: any = batchUpdateMock.mock.calls.find((call: any) => (call[0] as any).id === "ref1");
    expect(ref1Call).toBeDefined();
    expect(ref1Call[1].identityPropagationVersion).toBe(5);
    expect(ref1Call[1].identityVersion).toBeUndefined();

    // Check ref3 (legacy) update payload: must set identityPropagationVersion = 5
    const ref3Call: any = batchUpdateMock.mock.calls.find((call: any) => (call[0] as any).id === "ref3");
    expect(ref3Call).toBeDefined();
    expect(ref3Call[1].identityPropagationVersion).toBe(5);

    // Check that we DID NOT update the same-version document (ref2)
    const hasRef2 = batchUpdateMock.mock.calls.some((call) => (call[0] as any).id === "ref2");
    expect(hasRef2).toBe(false);
  });
});
