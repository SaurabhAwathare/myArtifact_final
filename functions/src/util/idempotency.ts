import * as admin from "firebase-admin";
import {FieldValue} from "firebase-admin/firestore";
import {logger} from "./logger";

/**
 * Ensures that a task is only executed once for a given idempotency key.
 * Stores the result/status in a dedicated 'idempotency_keys' collection.
 * Passes the transaction instance to the task so all side-effects and idempotency state
 * are committed in a single atomic transaction.
 */
export async function withIdempotency(
  key: string,
  task: (transaction: admin.firestore.Transaction) => Promise<any>
): Promise<any> {
  const db = admin.firestore();
  const keyRef = db.collection("idempotency_keys").doc(key);

  try {
    return await db.runTransaction(async (transaction) => {
      const doc = await transaction.get(keyRef);

      if (doc.exists) {
        const data = doc.data();
        if (data?.status === "SUCCESS") {
          logger.info(`Idempotency: Key ${key} already succeeded. Returning cached result.`);
          return data.result;
        }
        if (data?.status === "PROCESSING") {
          throw new Error(`Idempotency: Key ${key} is currently being processed.`);
        }
      }

      // Execute task within the transaction
      const result = await task(transaction);

      // Record SUCCESS within the SAME transaction
      transaction.set(keyRef, {
        status: "SUCCESS",
        result: result || null,
        completedAt: FieldValue.serverTimestamp(),
      });

      return result;
    });
  } catch (error) {
    logger.error(`Idempotency error for key ${key}:`, error);
    throw error;
  }
}
