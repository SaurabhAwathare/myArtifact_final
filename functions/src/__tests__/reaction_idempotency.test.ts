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
    const transaction = {
      get: jest.fn<any>().mockResolvedValue({ exists: true, data: () => ({ reactionCount: 0 }) }),
      set: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
    };
    return cb(transaction);
  }),
};

jest.mock("firebase-admin", () => {
  return {
    initializeApp: jest.fn(),
    apps: [] as any[],
    firestore: Object.assign(() => mockFirestore, {
      FieldValue: {
        increment: (n: number) => ({ operand: n }),
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

  it("onReactionCreated should use idempotency key with eventId and execute transactional counter updates", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionCreated);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_event_id_1";
    let mockTx: any;

    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      mockTx = {
        get: jest.fn<any>().mockResolvedValue({ exists: true, data: () => ({ reactionCount: 5 }) }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(mockTx);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    // Verify idempotency collection was checked with prefixed eventId
    expect(mockFirestore.collection).toHaveBeenCalledWith("idempotency_keys");
    expect(mockCollection.doc).toHaveBeenCalledWith(`react_inc_${eventId}`);

    // Verify transactional set and update were invoked with operand: 1
    expect(mockTx.set).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        totalCount: { operand: 1 },
        "breakdown.love": { operand: 1 }
      }),
      { merge: true }
    );
    expect(mockTx.update).toHaveBeenCalledWith(
      expect.anything(),
      { reactionCount: { operand: 1 } }
    );
    // Verify idempotency doc set status SUCCESS within transaction
    expect(mockTx.set).toHaveBeenCalledWith(
      mockDoc,
      expect.objectContaining({ status: "SUCCESS" })
    );
  });

  it("onReactionCreated duplicate delivery should be suppressed by existing idempotency record", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionCreated);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_duplicate_event_id";
    let mockTx: any;

    // Simulate idempotency key already exists with SUCCESS
    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      mockTx = {
        get: jest.fn<any>().mockResolvedValue({
          exists: true,
          data: () => ({ status: "SUCCESS", result: null })
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(mockTx);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    // Verify idempotency key was read
    expect(mockTx.get).toHaveBeenCalled();
    // Verify NO transactional counter updates occurred on duplicate
    expect(mockTx.update).not.toHaveBeenCalled();
  });

  it("onReactionDeleted should use idempotency key with eventId and execute transactional decrements", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionDeleted);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_event_id_del_1";
    let mockTx: any;

    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      mockTx = {
        get: jest.fn<any>().mockResolvedValue({ exists: true, data: () => ({ reactionCount: 5 }) }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(mockTx);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    expect(mockCollection.doc).toHaveBeenCalledWith(`react_dec_${eventId}`);

    // Verify transactional set and update were invoked with operand: -1
    expect(mockTx.set).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        totalCount: { operand: -1 },
        "breakdown.love": { operand: -1 }
      }),
      { merge: true }
    );
    expect(mockTx.update).toHaveBeenCalledWith(
      expect.anything(),
      { reactionCount: { operand: -1 } }
    );
  });

  it("onReactionDeleted duplicate delivery should be suppressed by existing idempotency record", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionDeleted);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_duplicate_del_event_id";
    let mockTx: any;

    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      mockTx = {
        get: jest.fn<any>().mockResolvedValue({
          exists: true,
          data: () => ({ status: "SUCCESS", result: null })
        }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(mockTx);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    });

    expect(mockTx.get).toHaveBeenCalled();
    expect(mockTx.update).not.toHaveBeenCalled();
  });

  it("a later legitimate create lifecycle event with distinct eventId is not suppressed by previous idempotency doc", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionCreated);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const newEventId = "test_new_lifecycle_event_123";
    let mockTx: any;

    // Simulate key for newEventId does not exist yet
    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      mockTx = {
        get: jest.fn<any>().mockResolvedValue({ exists: false }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(mockTx);
    });

    await wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: newEventId,
    });

    expect(mockCollection.doc).toHaveBeenCalledWith(`react_inc_${newEventId}`);
    expect(mockTx.set).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ totalCount: { operand: 1 } }),
      { merge: true }
    );
  });

  it("failure before transaction commit throws error and leaves no success idempotency record", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionCreated);
    const snapshot = {
      data: () => ({ artifactId: "art1", type: "love" }),
      exists: true
    } as any;

    const eventId = "test_failure_event";

    // Simulate transaction throwing error
    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      const mockTx = {
        get: jest.fn<any>().mockRejectedValue(new Error("Firestore storage transaction collision")),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(mockTx);
    });

    await expect(wrapped(snapshot, {
      params: { reactionId: "art1_user1" },
      eventId: eventId,
    })).rejects.toThrow("Firestore storage transaction collision");
  });

  it("onReactionIntentCreated should reject self-reaction when ownerId matches uid", async () => {
    const wrapped = testEnv.wrap(myFunctions.onReactionIntentCreated);
    const deleteFn = jest.fn(() => Promise.resolve());
    const snapshot = {
      data: () => ({ artifactId: "art1", action: "ADD", type: "RESONATE" }),
      ref: { delete: deleteFn },
    } as any;

    (mockFirestore.runTransaction as any).mockImplementationOnce(async (cb: (t: any) => Promise<any>) => {
      const transaction = {
        get: jest.fn<any>().mockResolvedValue({ exists: false }),
        set: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      };
      return cb(transaction);
    });

    // Mock artifactDoc
    mockDoc.get.mockImplementation(async () => {
      return {
        exists: true,
        data: () => ({
          author: { anonymousId: "anon_creator" },
          isPublic: true,
        }),
      };
    });

    // Mock persona_mapping doc lookup returning same user (ownerId == uid)
    mockCollection.get.mockImplementation(async () => ({
      empty: false,
      docs: [{ id: "user_creator" }],
    }));

    await wrapped(snapshot, {
      params: { uid: "user_creator", artifactId: "art1" },
      eventId: "test_self_reaction_event",
    });

    // Verify intent snapshot was deleted (self-reaction rejected)
    expect(deleteFn).toHaveBeenCalled();
  });
});
