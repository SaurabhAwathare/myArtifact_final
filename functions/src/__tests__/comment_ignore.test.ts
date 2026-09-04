import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mock Firestore
const mockDoc: any = {
  get: jest.fn(),
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
  get: jest.fn(),
  add: jest.fn(() => Promise.resolve({ id: "new_id" })),
};

mockDoc.collection.mockReturnValue(mockCollection);

const mockFirestore: any = {
  collection: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  runTransaction: jest.fn(async (cb: any) => cb({
    get: jest.fn(),
    set: jest.fn(),
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

// Mock withIdempotency to just run the callback
jest.mock("../util/idempotency", () => ({
  withIdempotency: jest.fn((key, cb: any) => cb()),
}));

describe("onCommentCreated Ignore Check", () => {
  beforeEach(() => {
    jest.clearAllMocks();
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
      data: () => ({ status: "ACTIVE", text: "Hello", creatorId: commenterId }),
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
      if (name === "notifications") return mockCollection;
      return mockCollection;
    });

    await wrapped(snapshot, {
      params: { artifactId, commentId },
    });

    // Should NOT have added a notification
    expect(mockCollection.add).not.toHaveBeenCalled();
  });

  it("should create notification if commenter is NOT ignored", async () => {
    const artifactId = "art123";
    const commentId = "com123";
    const ownerId = "ownerA";
    const commenterId = "commenterB";
    const wrapped = testEnv.wrap(myFunctions.onCommentCreated);

    const snapshot = {
      data: () => ({ status: "ACTIVE", text: "Hello", creatorId: commenterId }),
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
      if (name === "notifications") return mockCollection;
      return mockCollection;
    });

    await wrapped(snapshot, {
      params: { artifactId, commentId },
    });

    // Should have added a notification
    expect(mockCollection.add).toHaveBeenCalledWith(expect.objectContaining({
      userId: ownerId,
      type: "COMMENT"
    }));
  });
});
