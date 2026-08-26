import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocking Firebase Admin Firestore
jest.mock("firebase-admin/firestore", () => {
    class Timestamp {
        constructor(public seconds: number, public nanoseconds: number) {}
        static now() { return new Timestamp(Math.floor(Date.now() / 1000), 0); }
        static fromDate(date: Date) { return new Timestamp(Math.floor(date.getTime() / 1000), 0); }
        toMillis() { return this.seconds * 1000; }
    }
    return {
        FieldValue: {
            increment: (n: number) => ({ increment: n }),
            serverTimestamp: () => ({ timestamp: "now" }),
        },
        Timestamp
    };
});

// Comprehensive Mock for Firebase Admin
jest.mock("firebase-admin", () => {
    const createMockDoc = (id: string, data: any = {}) => ({
        id,
        exists: true,
        data: jest.fn(() => data),
        ref: {
            id,
            update: jest.fn(() => Promise.resolve()),
        }
    });

    const firestoreMock: any = {
        collection: jest.fn(() => firestoreMock),
        collectionGroup: jest.fn(() => firestoreMock),
        where: jest.fn(() => firestoreMock),
        orderBy: jest.fn(() => firestoreMock),
        startAfter: jest.fn(() => firestoreMock),
        limit: jest.fn(() => firestoreMock),
        get: jest.fn(() => Promise.resolve({ empty: true, size: 0, docs: [] as any[] })),
        doc: jest.fn((id: string) => createMockDoc(id)),
        batch: jest.fn(() => ({
            update: jest.fn(),
            commit: jest.fn(() => Promise.resolve({})),
        })),
        runTransaction: jest.fn(async (cb: any) => {
            const transaction = {
                get: jest.fn(() => Promise.resolve(createMockDoc("trans_doc"))),
                update: jest.fn(),
            };
            return cb(transaction);
        }),
    };

    return {
        initializeApp: jest.fn(),
        apps: [] as any[],
        firestore: Object.assign(() => firestoreMock, {
            FieldValue: {
                increment: (n: number) => ({ increment: n }),
                serverTimestamp: () => ({ timestamp: "now" }),
            },
            Timestamp: class {
                constructor(public seconds: number, public nanoseconds: number) {}
                static now() { return new (this as any)(Math.floor(Date.now() / 1000), 0); }
                toMillis() { return this.seconds * 1000; }
            }
        }),
    };
});

describe("onUserIdentityReset", () => {
    let adminFirestore: any;

    beforeEach(() => {
        jest.clearAllMocks();
        adminFirestore = admin.firestore();
    });

    it("should trigger propagation when identityResetVersion increases", async () => {
        const uid = "user_123";
        const beforeData = {
            identityMetadata: { identityResetVersion: 1 }
        };
        const afterData = {
            anonymousName: "New Persona",
            anonymousId: "anon_999",
            identityMetadata: { identityResetVersion: 2 }
        };

        const change = testEnv.makeChange(
            testEnv.firestore.makeDocumentSnapshot(beforeData, `users/${uid}`),
            testEnv.firestore.makeDocumentSnapshot(afterData, `users/${uid}`)
        );

        const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

        // Mock Artifacts and Comments query results
        const mockArtifact = { ref: { update: jest.fn() } };
        const mockComment = { ref: { update: jest.fn() } };

        (adminFirestore.get as any)
            .mockResolvedValueOnce({ empty: false, size: 1, docs: [mockArtifact] })
            .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }) // Artifacts end
            .mockResolvedValueOnce({ empty: false, size: 1, docs: [mockComment] })
            .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }); // Comments end

        await wrapped(change, { params: { uid } });

        // Verify Artifacts propagation
        expect(adminFirestore.collection).toHaveBeenCalledWith("artifacts");
        // Verify orderBy("__name__") is used for pagination
        expect(adminFirestore.orderBy).toHaveBeenCalledWith("__name__");

        // Verify Comments propagation
        expect(adminFirestore.collectionGroup).toHaveBeenCalledWith("comments");

        // Verify lastCompletedIdentityVersion update
        expect(adminFirestore.runTransaction).toHaveBeenCalled();
    });

    it("should only update documents with OLDER identity version", async () => {
        const uid = "user_123";
        const newVersion = 5;
        const beforeData = { identityMetadata: { identityResetVersion: 4 } };
        const afterData = {
            anonymousName: "New Persona",
            identityMetadata: { identityResetVersion: newVersion }
        };

        const change = testEnv.makeChange(
            testEnv.firestore.makeDocumentSnapshot(beforeData, `users/${uid}`),
            testEnv.firestore.makeDocumentSnapshot(afterData, `users/${uid}`)
        );

        const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);

        const batchUpdateMock = jest.fn();
        (adminFirestore.batch as jest.Mock).mockReturnValue({
            update: batchUpdateMock,
            commit: jest.fn(() => Promise.resolve({})),
        });

        // Mock 3 documents: one old, one same, one newer
        const docOld = { ref: "ref1", data: () => ({ identityVersion: 3 }) };
        const docSame = { ref: "ref2", data: () => ({ identityVersion: 5 }) };
        const docNewer = { ref: "ref3", data: () => ({ identityVersion: 6 }) };

        (adminFirestore.get as any)
            .mockResolvedValueOnce({ empty: false, size: 3, docs: [docOld, docSame, docNewer] })
            .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }) // Artifacts end
            .mockResolvedValue({ empty: true, size: 0, docs: [] }); // Others end

        await wrapped(change, { params: { uid } });

        // Should only call update for the document with version 3
        expect(batchUpdateMock).toHaveBeenCalledTimes(1);
        expect(batchUpdateMock).toHaveBeenCalledWith("ref1", expect.objectContaining({
            identityVersion: newVersion
        }));
    });

    it("should NOT trigger propagation if version remains same", async () => {
        const uid = "user_123";
        const beforeData = { identityMetadata: { identityResetVersion: 1 } };
        const afterData = { identityMetadata: { identityResetVersion: 1 } };

        const change = testEnv.makeChange(
            testEnv.firestore.makeDocumentSnapshot(beforeData, `users/${uid}`),
            testEnv.firestore.makeDocumentSnapshot(afterData, `users/${uid}`)
        );

        const wrapped = testEnv.wrap(myFunctions.onUserIdentityReset);
        await wrapped(change, { params: { uid } });

        expect(adminFirestore.collection).not.toHaveBeenCalled();
    });
});
