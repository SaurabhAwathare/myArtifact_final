/**
 * Administrative script to backfill private suppression markers for existing reports.
 * Performs a safe "touch" on report documents to trigger the onReportWrite Cloud Function.
 */
const admin = require('firebase-admin');
const { FieldValue } = require('firebase-admin/firestore');

// Initialize Admin SDK
// Required: GOOGLE_APPLICATION_CREDENTIALS environment variable or active firebase login
if (!admin.apps.length) {
    admin.initializeApp();
}

const db = admin.firestore();

async function backfillReportMarkers() {
    console.log('Starting Safety Sync Reconciliation (Backfill)...');

    const reportsRef = db.collection('reports');
    let lastDoc = null;
    let totalProcessed = 0;
    let totalSkipped = 0;

    while (true) {
        // Use document ID ordering for stable pagination
        let query = reportsRef.orderBy('__name__').limit(500);
        if (lastDoc) {
            query = query.startAfter(lastDoc);
        }

        const snapshot = await query.get();
        if (snapshot.empty) {
            break;
        }

        const batch = db.batch();
        let batchUpdateCount = 0;

        snapshot.docs.forEach(doc => {
            const data = doc.data();

            // Skip documents that have already been reconciled in a previous run
            if (!data._reconciledAt) {
                batch.update(doc.ref, {
                    '_reconciledAt': FieldValue.serverTimestamp()
                });
                batchUpdateCount++;
            } else {
                totalSkipped++;
            }
            lastDoc = doc;
        });

        if (batchUpdateCount > 0) {
            await batch.commit();
            totalProcessed += batchUpdateCount;
            console.log(`Processed batch: ${batchUpdateCount} updated, ${snapshot.size - batchUpdateCount} skipped. Total updated: ${totalProcessed}`);
        } else {
            console.log(`Batch of ${snapshot.size} already reconciled. Skipping.`);
        }

        // Small delay to ensure we don't overwhelm the write throughput limits
        await new Promise(resolve => setTimeout(resolve, 200));

        if (snapshot.size < 500) {
            break;
        }
    }

    console.log('--- Backfill Summary ---');
    console.log(`Total Reports Updated: ${totalProcessed}`);
    console.log(`Total Reports Skipped: ${totalSkipped}`);
    console.log('Reconciliation Complete.');
}

backfillReportMarkers().catch(err => {
    console.error('Reconciliation FATAL ERROR:', err);
    process.exit(1);
});
