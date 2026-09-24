import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Improved Mocking for Deletion Tests
const mockBulkWriter = {
  update: jest.fn(),
  delete: jest.fn(),
  close: jest.fn(() => Promise.resolve()),
};

const mockBucket = {
  file: jest.fn().mockReturnThis(),
  delete: jest.fn(() => Promise.resolve({})),
  deleteFiles: jest.fn(() => Promise.resolve()),
  getFiles: jest.fn(() => Promise.resolve([[]])),
};

const mockDoc: any = {
  id: "mock_id",
  exists: true,
  data: jest.fn(() => ({})),
  get: jest.fn(),
  set: jest.fn(() => Promise.resolve({})),
  update: jest.fn(() => Promise.resolve({})),
  delete: jest.fn(() => Promise.resolve({})),
  collection: jest.fn(),
  ref: {
    id: "mock_id",
    delete: jest.fn(() => Promise.resolve()),
    update: jest.fn(() => Promise.resolve()),
    collection: jest.fn().mockReturnThis(),
  }
};

const mockCollection: any = {
  doc: jest.fn((id?: string) => mockFirestore.doc(id || "mock_id")),
  where: jest.fn().mockReturnThis(),
  limit: jest.fn().mockReturnThis(),
  get: jest.fn(),
  orderBy: jest.fn().mockReturnThis(),
};

mockDoc.collection.mockReturnValue(mockCollection);
mockDoc.get.mockResolvedValue(mockDoc);

const mockFirestore: any = {
  collection: jest.fn((colName: string) => ({
    ...mockCollection,
    doc: jest.fn((docId: string) => mockFirestore.doc(`${colName}/${docId}`)),
  })),
  collectionGroup: jest.fn(() => mockCollection),
  doc: jest.fn(() => mockDoc),
  batch: jest.fn(() => ({
    set: jest.fn(),
    update: jest.fn(),
    commit: jest.fn(() => Promise.resolve({})),
    delete: jest.fn(),
  })),
  runTransaction: jest.fn(async (cb: any) => {
    return cb({
      get: jest.fn(() => Promise.resolve(mockDoc)),
      set: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
    });
  }),
  recursiveDelete: jest.fn(() => Promise.resolve()),
  bulkWriter: jest.fn(() => mockBulkWriter),
  get: jest.fn(),
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

describe("Account Deletion Pipeline", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockDoc.data.mockReturnValue({});
    mockDoc.exists = true;
    mockCollection.get.mockResolvedValue({ docs: [], size: 0, empty: true });
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  describe("onUserDeleted", () => {
    it("should perform comprehensive remote cleanup when a user is deleted", async () => {
      const uid = "user_to_delete";
      const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

      const mockArtifacts = [
        { ref: { id: "art1" }, data: () => ({ status: "ACTIVE" }) },
        { ref: { id: "art2" }, data: () => ({ status: "ACTIVE" }) }
      ];

      let artifactsFetched = false;
      mockCollection.get.mockImplementation(async () => {
        const mockCalls = (mockCollection.where as jest.Mock).mock.calls;
        const lastWhereCall = mockCalls[mockCalls.length - 1];

        if (lastWhereCall && lastWhereCall[0] === "userId" && lastWhereCall[2] === uid) {
          if (!artifactsFetched) {
            artifactsFetched = true;
            return { empty: false, size: 2, docs: mockArtifacts };
          }
        }
        return { empty: true, size: 0, docs: [] };
      });

      mockDoc.data.mockReturnValue({ anonymousName: "Alice" });

      await wrapped({ uid } as any);

      expect(mockBucket.getFiles).toHaveBeenCalledWith({ prefix: `backups/${uid}/` });
      expect(mockBulkWriter.delete).toHaveBeenCalledTimes(2);
      expect(mockBulkWriter.close).toHaveBeenCalled();
      expect(mockFirestore.recursiveDelete).toHaveBeenCalled();
    });

    it("should anonymize comments during user deletion", async () => {
      const uid = "commenter_uid";
      const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

      // Use a more flexible mock for paged queries
      let commentsFetched = false;
      mockCollection.get.mockImplementation(async () => {
        // Find which collection is being queried by checking the last "collection" call if possible,
        // but here we can just use a sequence that matches the code flow.
        // Or better, check if where("creatorId", "==", uid) was used.
        const mockCalls = (mockCollection.where as jest.Mock).mock.calls;
        const lastWhereCall = mockCalls[mockCalls.length - 1];

        if (lastWhereCall && lastWhereCall[0] === "creatorId") {
          if (!commentsFetched) {
            commentsFetched = true;
            return {
              empty: false,
              size: 3,
              docs: [{ ref: {}, data: () => ({}) }, { ref: {}, data: () => ({}) }, { ref: {}, data: () => ({}) }]
            };
          }
        }
        return { empty: true, size: 0, docs: [] };
      });

      await wrapped({ uid } as any);

      expect(mockBulkWriter.update).toHaveBeenCalledWith(expect.anything(), { creatorId: "" });
    });
  });

  describe("onArtifactCleanupTrigger", () => {
    it("should use predictable path fallback if URLs are missing", async () => {
      const artifactId = "orphaned_art";
      const userId = "user123";
      const wrapped = testEnv.wrap(myFunctions.onArtifactCleanupTrigger);

      const beforeSnapshot = { data: () => ({ status: "ACTIVE" }) };
      const afterSnapshot = {
        data: () => ({
          status: "DELETED",
          userId: userId,
        }),
        ref: {
          delete: jest.fn(() => Promise.resolve()),
          collection: jest.fn().mockReturnThis(),
        }
      };

      // Mock the latest document state for the safety guard
      mockDoc.exists = true;
      mockDoc.data.mockReturnValue({ moderation: { legalHold: false }, userId });

      await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
        params: { artifactId },
        eventId: "cleanup_fallback",
      } as any);

      expect(mockBucket.file).toHaveBeenCalledWith(`artifacts/${userId}_${artifactId}.m4a`);
      expect(mockBucket.file).toHaveBeenCalledWith(`transcripts/${userId}_${artifactId}.json`);
    });
  });

  describe("deleteArtifact Callable Function", () => {
    function makeSnap(exists: boolean, dataObj: any, docPath: string = "") {
      const snap: any = {
        exists,
        data: () => dataObj,
        update: mockDoc.update,
        collection: (colName: string) => ({
          doc: (id: string) => {
            const childPath = docPath ? `${docPath}/${colName}/${id}` : `${colName}/${id}`;
            return mockFirestore.doc(childPath);
          },
        }),
      };
      snap.get = jest.fn(() => Promise.resolve(snap));
      return snap;
    }

    it("TEST 1: Authenticated Creator + modern Artifact with matching userId -> deletion authorized", async () => {
      const callerUid = "user_owner_123";
      const artifactId = "art_modern_1";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      const artifactData = {
        userId: callerUid,
        status: "ACTIVE",
        isPublic: true,
      };

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, artifactData, path);
        }
        if (path === `users/${callerUid}/private/settings`) {
          return makeSnap(false, {}, path);
        }
        return makeSnap(false, {}, path);
      });

      const res = await wrapped({ artifactId }, { auth: { uid: callerUid, token: { activeSessionId: "session_123" } } });

      expect(res).toEqual({ status: "SUCCESS", artifactId });
      expect(mockDoc.update).toHaveBeenCalledWith(
        expect.objectContaining({
          status: "DELETED",
          isPublic: false,
        })
      );
    });

    it("TEST 2: Authenticated Creator + legacy Artifact with missing userId but valid persona_mapping -> deletion authorized", async () => {
      const callerUid = "user_owner_123";
      const anonId = "anon_persona_abc";
      const artifactId = "art_legacy_1";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      const artifactData = {
        author: { anonymousId: anonId },
        status: "ACTIVE",
        isPublic: true,
      };

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, artifactData, path);
        }
        if (path === `persona_mapping/${anonId}`) {
          return makeSnap(true, { userId: callerUid }, path);
        }
        if (path === `users/${callerUid}/private/settings`) {
          return makeSnap(false, {}, path);
        }
        return makeSnap(false, {}, path);
      });

      const res = await wrapped({ artifactId }, { auth: { uid: callerUid, token: { activeSessionId: "session_123" } } });

      expect(res).toEqual({ status: "SUCCESS", artifactId });
      expect(mockDoc.update).toHaveBeenCalledWith(
        expect.objectContaining({
          status: "DELETED",
          isPublic: false,
        })
      );
    });

    it("TEST 3: Authenticated User A + Artifact owned by User B -> permission-denied", async () => {
      const userA = "user_A";
      const userB = "user_B";
      const artifactId = "art_user_B";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, { userId: userB, status: "ACTIVE" }, path);
        }
        if (path === `users/${userA}/private/settings`) {
          return makeSnap(false, {}, path);
        }
        return makeSnap(false, {}, path);
      });

      await expect(wrapped({ artifactId }, { auth: { uid: userA, token: { activeSessionId: "session_A" } } })).rejects.toThrow("Unauthorized: You do not own this reflection.");
      expect(mockDoc.update).not.toHaveBeenCalled();
    });

    it("TEST 4: Authenticated User A + fake private registry entry pointing to User B's Artifact -> permission-denied", async () => {
      const userA = "user_attacker";
      const userB = "user_victim";
      const artifactId = "victim_art";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, { userId: userB, status: "ACTIVE" }, path);
        }
        return makeSnap(false, {}, path);
      });

      await expect(wrapped({ artifactId }, { auth: { uid: userA, token: { activeSessionId: "session_A" } } })).rejects.toThrow("Unauthorized: You do not own this reflection.");
      expect(mockDoc.update).not.toHaveBeenCalled();
    });

    it("TEST 5: Client supplies userId = User B while authenticated as User A -> supplied userId ignored -> permission-denied", async () => {
      const userA = "user_A";
      const userB = "user_B";
      const artifactId = "art_user_B";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, { userId: userB, status: "ACTIVE" }, path);
        }
        return makeSnap(false, {}, path);
      });

      await expect(wrapped({ artifactId, userId: userB }, { auth: { uid: userA, token: { activeSessionId: "session_A" } } })).rejects.toThrow("Unauthorized: You do not own this reflection.");
      expect(mockDoc.update).not.toHaveBeenCalled();
    });

    it("TEST 6: Artifact with conflicting ownership information -> safely deny with permission-denied", async () => {
      const userA = "user_attacker";
      const userB = "user_canonical_owner";
      const anonId = "claimed_anon_id";
      const artifactId = "art_conflict";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, { userId: userB, author: { anonymousId: anonId }, status: "ACTIVE" }, path);
        }
        if (path === `persona_mapping/${anonId}`) {
          return makeSnap(true, { userId: userA }, path);
        }
        return makeSnap(false, {}, path);
      });

      await expect(wrapped({ artifactId }, { auth: { uid: userA, token: { activeSessionId: "session_A" } } })).rejects.toThrow("Unauthorized: You do not own this reflection.");
      expect(mockDoc.update).not.toHaveBeenCalled();
    });

    it("TEST 7: Artifact does not exist -> idempotent success / already deleted response", async () => {
      const callerUid = "user_123";
      const artifactId = "non_existent_art";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(false, null, path);
        }
        return makeSnap(false, {}, path);
      });

      const res = await wrapped({ artifactId }, { auth: { uid: callerUid, token: { activeSessionId: "session_123" } } });
      expect(res).toEqual({ status: "SUCCESS", message: "Artifact already deleted.", artifactId });
    });

    it("TEST 8: Unauthenticated caller -> unauthenticated", async () => {
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      await expect(wrapped({ artifactId: "art_123" }, { auth: null as any })).rejects.toThrow("Authentication required.");
    });

    it("TEST 9: Malformed / missing artifactId -> invalid-argument", async () => {
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      await expect(wrapped({}, { auth: { uid: "user_123", token: { activeSessionId: "session_123" } } })).rejects.toThrow("Valid artifactId is required.");
      await expect(wrapped({ artifactId: "" }, { auth: { uid: "user_123", token: { activeSessionId: "session_123" } } })).rejects.toThrow("Valid artifactId is required.");
    });

    it("TEST 10: Admin deletion -> global admin can delete any artifact", async () => {
      const adminUid = "admin_user_999";
      const userB = "user_B";
      const artifactId = "art_user_B";
      const wrapped = testEnv.wrap(myFunctions.deleteArtifact);

      mockFirestore.doc.mockImplementation((path: string) => {
        if (path === `artifacts/${artifactId}`) {
          return makeSnap(true, { userId: userB, status: "ACTIVE" }, path);
        }
        if (path === `users/${adminUid}/private/settings`) {
          return makeSnap(true, { isAdmin: true }, path);
        }
        return makeSnap(false, {}, path);
      });

      const res = await wrapped({ artifactId }, { auth: { uid: adminUid, token: { activeSessionId: "session_admin" } } });
      expect(res).toEqual({ status: "SUCCESS", artifactId });
      expect(mockDoc.update).toHaveBeenCalledWith(
        expect.objectContaining({
          status: "DELETED",
          isPublic: false,
        })
      );
    });
  });
});

