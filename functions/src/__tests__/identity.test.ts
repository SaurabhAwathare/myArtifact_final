import * as admin from "firebase-admin";
import functionsTest from "firebase-functions-test";
import * as myFunctions from "../index";
import { describe, it, expect, beforeEach, jest } from "@jest/globals";

const testEnv = functionsTest();

// Mocking Firebase Admin Firestore
jest.mock("firebase-admin/firestore", () => ({
    FieldValue: {
        increment: (n: number) => ({ increment: n }),
        serverTimestamp: () => ({ timestamp: "now" }),
    },
}));

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
        limit: jest.fn(() => firestoreMock),
        get: jest.fn(() => Promise.resolve({ empty: true, size: 0, docs: [] })),
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

        (adminFirestore.get as jest.Mock)
            .mockResolvedValueOnce({ empty: false, size: 1, docs: [mockArtifact] })
            .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }) // Artifacts end
            .mockResolvedValueOnce({ empty: false, size: 1, docs: [mockComment] })
            .mockResolvedValueOnce({ empty: true, size: 0, docs: [] }); // Comments end

        await wrapped(change, { params: { uid } });

        // Verify Artifacts propagation
        expect(adminFirestore.collection).toHaveBeenCalledWith("artifacts");

        // Verify Comments propagation
        expect(adminFirestore.collectionGroup).toHaveBeenCalledWith("comments");

        // Verify lastCompletedIdentityVersion update
        expect(adminFirestore.runTransaction).toHaveBeenCalled();
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
