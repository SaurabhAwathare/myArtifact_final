import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeAll, afterAll, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocking Firebase Admin Firestore
jest.mock("firebase-admin/firestore", () => ({
    FieldValue: {
        increment: (n: number) => ({ increment: n }),
        serverTimestamp: () => ({ timestamp: "now" }),
    },
}));

const mockBulkWriter = {
    update: jest.fn(),
    close: jest.fn(() => Promise.resolve()),
};

const mockBucket = {
    file: jest.fn().mockReturnThis(),
    delete: jest.fn(() => Promise.resolve({})),
    deleteFiles: jest.fn(() => Promise.resolve()),
    getFiles: jest.fn(() => Promise.resolve([[]])),
};

// Comprehensive Mock for Firebase Admin
jest.mock("firebase-admin", () => {
    const createMockDoc = (id: string, data: any = {}) => ({
        id,
        exists: true,
        data: jest.fn(() => data),
        ref: {
            id,
            delete: jest.fn(() => Promise.resolve()),
            update: jest.fn(() => Promise.resolve()),
            collection: jest.fn().mockReturnThis(),
        }
    });

    const createMockQuerySnapshot = (docs: any[] = []) => ({
        empty: docs.length === 0,
        size: docs.length,
        docs: docs
    });

    const createQueryMock = () => ({
        where: jest.fn().mockReturnThis(),
        limit: jest.fn().mockReturnThis(),
        orderBy: jest.fn().mockReturnThis(),
        get: jest.fn(() => Promise.resolve(createMockQuerySnapshot())),
        doc: jest.fn((id: any) => Object.assign(createMockDoc(id), docMockExtras)),
    });

    const docMockExtras = {
        get: jest.fn(() => Promise.resolve(createMockDoc("mock_doc"))),
        delete: jest.fn(() => Promise.resolve()),
        update: jest.fn(() => Promise.resolve()),
        collection: jest.fn(() => createQueryMock()),
    };

    const firestoreMock = {
        collection: jest.fn(() => createQueryMock()),
        collectionGroup: jest.fn(() => createQueryMock()),
        doc: jest.fn((id: any) => Object.assign(createMockDoc(id), docMockExtras)),
        batch: jest.fn(() => ({
            set: jest.fn(),
            update: jest.fn(),
            commit: jest.fn(() => Promise.resolve({})),
            delete: jest.fn(),
        })),
        runTransaction: jest.fn(async (cb: any) => {
            const transaction = {
                get: jest.fn(() => Promise.resolve(createMockDoc("trans_doc"))),
                set: jest.fn(),
                update: jest.fn(),
                delete: jest.fn(),
            };
            return cb(transaction);
        }),
        recursiveDelete: jest.fn(() => Promise.resolve()),
        bulkWriter: jest.fn(() => mockBulkWriter),
    };

    return {
        initializeApp: jest.fn(),
        apps: [] as any[],
        firestore: Object.assign(() => firestoreMock, {
            FieldValue: {
                increment: (n: number) => ({ increment: n }),
                serverTimestamp: () => ({ timestamp: "now" }),
            },
        }),
        storage: Object.assign(() => ({
            bucket: () => mockBucket,
        }), {
            bucket: () => mockBucket,
        }),
    };
});

describe("Account Deletion Pipeline", () => {
    let db: any;

    beforeAll(() => {
        db = admin.firestore();
    });

    beforeEach(() => {
        jest.clearAllMocks();
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

            const mockArtifactsQuery = db.collection();
            (mockArtifactsQuery.get as any).mockResolvedValueOnce({ empty: false, size: 2, docs: mockArtifacts } as any);

            const mockUserDoc = db.doc();
            (mockUserDoc.get as any).mockResolvedValueOnce({ exists: true, data: () => ({ anonymousName: "Alice" }) } as any);

            await wrapped({ uid } as any);

            // 1. Verify Storage Backups Purge attempt
            expect(mockBucket.getFiles).toHaveBeenCalledWith({ prefix: `backups/${uid}/` });

            // 2. Verify Artifacts marked as DELETED via BulkWriter
            expect(mockBulkWriter.update).toHaveBeenCalledTimes(2);
            expect(mockBulkWriter.close).toHaveBeenCalled();

            // 3. Verify Recursive User Tree Destruction
            expect(db.recursiveDelete).toHaveBeenCalledWith(expect.anything());
        });

        it("should anonymize comments during user deletion", async () => {
            const uid = "commenter_uid";
            const wrapped = testEnv.wrap(myFunctions.onUserDeleted);

            // Mock artifacts query to be empty
            (db.collection() as any).get.mockResolvedValueOnce({ empty: true, size: 0, docs: [] } as any);

            // Mock user doc
            (db.doc() as any).get.mockResolvedValueOnce({ exists: true, data: () => ({}) } as any);

            // Mock comments collectionGroup query
            (db.collectionGroup() as any).get.mockResolvedValueOnce({
                empty: false,
                size: 3,
                docs: [{ ref: {}, data: () => ({}) }, { ref: {}, data: () => ({}) }, { ref: {}, data: () => ({}) }]
            } as any);

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

            await wrapped({ before: beforeSnapshot, after: afterSnapshot } as any, {
                params: { artifactId },
                eventId: "cleanup_fallback",
            } as any);

            // Verify predictable paths were tried
            expect(mockBucket.file).toHaveBeenCalledWith(`artifacts/${userId}_${artifactId}.m4a`);
            expect(mockBucket.file).toHaveBeenCalledWith(`transcripts/${userId}_${artifactId}.json`);
        });
    });
});
