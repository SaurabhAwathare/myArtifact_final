import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Shared Mocks
const mockDoc: any = {
  get: jest.fn(),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  id: "mock_doc_id"
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(),
};

const mockFirestore: any = {
  collection: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  batch: jest.fn(() => ({
    set: jest.fn(),
    update: jest.fn(),
    commit: jest.fn(() => Promise.resolve({})),
  })),
  runTransaction: jest.fn(async (cb: (t: any) => Promise<any>) => {
    return cb({
      get: jest.fn(() => Promise.resolve({ exists: false })), // For idempotency check
      set: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
    });
  }),
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
  };
});

describe("Reaction Idempotency", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  it("onReactionCreated should use idempotency key with eventId", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionCreated);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_event_id";

    // Simulate first run (idempotency doc doesn't exist)
    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
        const transaction = {
            get: jest.fn<any>().mockResolvedValue({ exists: false }),
            set: jest.fn(),
            update: jest.fn(),
            delete: jest.fn(),
        };
        return cb(transaction);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    // Verify idempotency collection was checked with prefixed eventId
    expect(mockFirestore.collection).toHaveBeenCalledWith("idempotency_keys");
    expect(mockCollection.doc).toHaveBeenCalledWith(`react_inc_${eventId}`);
  });

  it("onReactionDeleted should use idempotency key with eventId", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionDeleted);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_event_id_del";

    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
        const transaction = {
            get: jest.fn<any>().mockResolvedValue({ exists: false }),
            set: jest.fn(),
            update: jest.fn(),
            delete: jest.fn(),
        };
        return cb(transaction);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    expect(mockCollection.doc).toHaveBeenCalledWith(`react_dec_${eventId}`);
  });

  it("should skip execution if idempotency key already exists (retry safety)", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionCreated);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_retry_event";

    // Simulate already succeeded
    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
        const transaction = {
            get: jest.fn<any>().mockResolvedValue({
                exists: true,
                data: () => ({ status: "SUCCESS", result: null })
            }),
            set: jest.fn(),
            update: jest.fn(),
            delete: jest.fn(),
        };
        return cb(transaction);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    // Verify no batch was created (since task was skipped)
    expect(mockFirestore.batch).not.toHaveBeenCalled();
  });
});
