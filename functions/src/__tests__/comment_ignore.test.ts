import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mock Firestore
const mockDoc: any = {
  get: jest.fn(() => Promise.resolve({ exists: false, data: () => null })),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
  id: "mock_doc_id"
};

const mockCollection: any = {
  doc: jest.fn(() => mockDoc),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(() => Promise.resolve({ empty: true, size: 0, docs: [] })),
  add: jest.fn(() => Promise.resolve({ id: "new_id" })),
};

mockDoc.collection.mockReturnValue(mockCollection);

let transactionSetCalls: any[] = [];
let transactionUpdateCalls: any[] = [];

const mockFirestore: any = {
  collection: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  runTransaction: jest.fn(async (cb: any) => {
    transactionSetCalls = [];
    transactionUpdateCalls = [];
    const transaction = {
      get: jest.fn(async (docRef: any) => {
        if (docRef && typeof docRef.get === "function") {
          return docRef.get();
        }
        return { exists: false, data: () => null };
      }),
      set: jest.fn((docRef: any, data: any) => {
        transactionSetCalls.push({ docRef, data });
        if (docRef && typeof docRef.set === "function") {
          docRef.set(data);
        }
      }),
      update: jest.fn((docRef: any, data: any) => {
        transactionUpdateCalls.push({ docRef, data });
        if (docRef && typeof docRef.update === "function") {
          docRef.update(data);
        }
      }),
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
        increment: (n: number) => ({ increment: n }),
        serverTimestamp: () => ({ timestamp: "now" }),
      },
    }),
    storage: () => ({
      bucket: () => ({
        file: () => ({
          delete: jest.fn(() => Promise.resolve({})),
        }),
      }),
    }),
  };
});

describe("onCommentCreated Ignore Check", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    transactionSetCalls = [];
    transactionUpdateCalls = [];
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  it("should suppress notification if commenter is ignored by owner", async () => {
    const artifactId = "art123";
    const commentId = "com123";
    const ownerId = "ownerA";
    const commenterId = "commenterB";
    const wrapped = testEnv.wrap(myFunctions.onCommentCreated);

    const snapshot = {
      data: () => ({ status: "ACTIVE", text: "Hello", authorAnonymousId: "anonB", creatorId: commenterId }),
      exists: true
    } as any;

    const mockIgnoreDoc: any = { exists: true };
    const mockSettingsDoc: any = { exists: true, data: () => ({ notificationsEnabled: true }) };

    const ownerDoc: any = {
      collection: jest.fn((name: string) => {
        if (name === "private") {
          return {
            doc: jest.fn((id: string) => {
              if (id === "ignored_users") {
                return {
                  collection: jest.fn((sub: string) => ({
                    doc: jest.fn((uid: string) => ({
                      get: jest.fn().mockImplementation(() => Promise.resolve(mockIgnoreDoc))
                    }))
                  }))
                };
              }
              if (id === "settings") {
                return {
                  get: jest.fn().mockImplementation(() => Promise.resolve(mockSettingsDoc))
                };
              }
              return mockDoc;
            })
          };
        }
        return mockCollection;
      })
    };

    mockFirestore.collection.mockImplementation((name: string) => {
      if (name === "persona_mapping") {
        return {
          doc: jest.fn(() => ({
            get: jest.fn().mockImplementation(() => Promise.resolve({
              exists: true,
              data: () => ({ userId: commenterId }),
            }))
          }))
        };
      }
      if (name === "users") {
        return {
          doc: jest.fn((id: string) => {
            if (id === ownerId) return ownerDoc;
            return mockDoc;
          })
        };
      }
      if (name === "artifacts") {
        return {
          doc: jest.fn(() => ({
            get: jest.fn().mockImplementation(() => Promise.resolve({
              exists: true,
              data: () => ({ isPublic: true, userId: ownerId, title: "My Art" })
            })),
            update: jest.fn(() => Promise.resolve({})),
          }))
        };
      }
      if (name === "idempotency_keys" || name === "notifications") {
        return {
          doc: jest.fn((id: string) => ({
            get: jest.fn().mockImplementation(() => Promise.resolve({ exists: false, data: () => null })),
            set: jest.fn(),
          }))
        };
      }
      return mockCollection;
    });

    await wrapped(snapshot, {
      params: { artifactId, commentId },
    });

    // Notification set call should NOT be present for notifications
    const notifCall = transactionSetCalls.find(c => c.data?.type === "COMMENT");
    expect(notifCall).toBeUndefined();
  });

  it("should create notification if commenter is NOT ignored", async () => {
    const artifactId = "art123";
    const commentId = "com123";
    const ownerId = "ownerA";
    const commenterId = "commenterB";
    const wrapped = testEnv.wrap(myFunctions.onCommentCreated);

    const snapshot = {
      data: () => ({ status: "ACTIVE", text: "Hello", authorAnonymousId: "anonB", creatorId: commenterId }),
      exists: true
    } as any;

    const mockIgnoreDoc: any = { exists: false };
    const mockSettingsDoc: any = { exists: true, data: () => ({ notificationsEnabled: true }) };

    const ownerDoc: any = {
      collection: jest.fn((name: string) => {
        if (name === "private") {
          return {
            doc: jest.fn((id: string) => {
              if (id === "ignored_users") {
                return {
                  collection: jest.fn((sub: string) => ({
                    doc: jest.fn((uid: string) => ({
                      get: jest.fn().mockImplementation(() => Promise.resolve(mockIgnoreDoc))
                    }))
                  }))
                };
              }
              if (id === "settings") {
                return {
                  get: jest.fn().mockImplementation(() => Promise.resolve(mockSettingsDoc))
                };
              }
              return mockDoc;
            })
          };
        }
        return mockCollection;
      })
    };

    mockFirestore.collection.mockImplementation((name: string) => {
      if (name === "persona_mapping") {
        return {
          doc: jest.fn((id: string) => ({
            get: jest.fn().mockImplementation(() => Promise.resolve({
              exists: true,
              data: () => ({ userId: id === "ownerAnon" ? ownerId : commenterId }),
            }))
          }))
        };
      }
      if (name === "users") {
        return {
          doc: jest.fn((id: string) => {
            if (id === ownerId) return ownerDoc;
            return mockDoc;
          })
        };
      }
      if (name === "artifacts") {
        return {
          doc: jest.fn(() => ({
            get: jest.fn().mockImplementation(() => Promise.resolve({
              exists: true,
              data: () => ({ isPublic: true, userId: ownerId, author: { anonymousId: "ownerAnon" }, title: "My Art" })
            })),
            update: jest.fn(() => Promise.resolve({})),
          }))
        };
      }
      if (name === "idempotency_keys" || name === "notifications") {
        return {
          doc: jest.fn((id: string) => ({
            get: jest.fn().mockImplementation(() => Promise.resolve({ exists: false, data: () => null })),
            set: jest.fn(),
          }))
        };
      }
      return mockCollection;
    });

    await wrapped(snapshot, {
      params: { artifactId, commentId },
    });

    // Should have staged notification creation in transaction
    const notifCall = transactionSetCalls.find(c => c.data?.type === "COMMENT");
    expect(notifCall).toBeDefined();
    expect(notifCall.data).toEqual(expect.objectContaining({
      userId: ownerId,
      type: "COMMENT"
    }));
  });
});
