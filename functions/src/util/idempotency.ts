import * as admin from "firebase-admin";
import { logger } from "./logger";

/**
 * Ensures that a task is only executed once for a given idempotency key.
 * Stores the result/status in a dedicated 'idempotency_keys' collection.
 */
export async function withIdempotency(
  key: string,
  task: () => Promise<any>
): Promise<any> {
  const db = admin.firestore();
  const keyRef = db.collection("idempotency_keys").doc(key);

  try {
    return await db.runTransaction(async (transaction) => {
      const doc = await transaction.get(keyRef);

      if (doc.exists) {
        const data = doc.data();
        if (data?.status === 'SUCCESS') {
          logger.info(`Idempotency: Key ${key} already succeeded. Returning cached result.`);
          return data.result;
        }
        if (data?.status === 'PROCESSING') {
          throw new Error(`Idempotency: Key ${key} is currently being processed.`);
        }
      }

      // Mark as processing
      transaction.set(keyRef, {
        status: 'PROCESSING',
        startedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // Execute task
      try {
        const result = await task();

        transaction.update(keyRef, {
          status: 'SUCCESS',
          result: result || null,
          completedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        return result;
      } catch (error) {
        // Clear processing status on failure to allow retry
        transaction.delete(keyRef);
        throw error;
      }
    });
  } catch (error) {
    logger.error(`Idempotency error for key ${key}:`, error);
    throw error;
  }
}
