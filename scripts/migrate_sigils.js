/**
 * Artifact Sigil Migration Script (v2.0.0)
 *
 * Purpose: Restores visual identity to historical Artifacts and Comments by migrating
 * legacy authorAvatar* fields to authorSigil* fields and normalizing sigilConfig to Version 3.
 *
 * Safeguards:
 * - Default Dry-Run mode.
 * - Explicit --execute flag required for writes.
 * - Interactive confirmation prompt ("MIGRATE").
 * - Atomic per-document updates.
 * - Idempotent and resumable logic.
 * - Batching (default 500) with independent commits.
 * - Detailed migration report.
 *
 * Usage:
 * node scripts/migrate_sigils.js [--execute] [--batch-size 500] [--yes]
 */

const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

// --- Configuration ---
const FAILURE_LOG_PATH = path.join(__dirname, 'migration_failures.log.json');
const DEFAULT_BATCH_SIZE = 500;

// Initialize Admin SDK
if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3'
  });
}

const db = admin.firestore();

async function runMigration() {
  const args = process.argv.slice(2);
  const isExecute = args.includes('--execute');
  const skipConfirm = args.includes('--yes');
  const batchSize = parseInt(args.find(a => a.startsWith('--batch-size'))?.split('=')[1] || DEFAULT_BATCH_SIZE);

  console.log('\n--- Artifact Sigil Migration v2.0.0 ---');
  console.log(`Mode: ${isExecute ? 'EXECUTE' : 'DRY RUN'}`);
  console.log(`Batch Size: ${batchSize}`);
  console.log('---------------------------------------\n');

  if (isExecute && !skipConfirm) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const answer = await new Promise(resolve => rl.question('⚠️ CAUTION: You are about to modify historical data. Type "MIGRATE" to continue: ', resolve));
    rl.close();
    if (answer !== 'MIGRATE') {
      console.log('Migration cancelled by operator.');
      process.exit(0);
    }
  }

  const startTime = Date.now();
  const globalMetrics = {
    artifacts: { scanned: 0, migrated: 0, alreadyClean: 0, failed: 0 },
    comments: { scanned: 0, migrated: 0, alreadyClean: 0, failed: 0 },
    fieldsRemoved: { avatarSeed: 0, avatarColor: 0, avatarConfig: 0 }
  };
  const allFailures = [];

  try {
    // 1. Migrate Artifacts
    console.log('>>> Starting Artifacts migration...');
    await migrateCollection(
      db.collection('artifacts'),
      'artifacts',
      globalMetrics.artifacts,
      globalMetrics.fieldsRemoved,
      allFailures,
      isExecute,
      batchSize
    );

    // 2. Migrate Comments (Collection Group)
    console.log('\n>>> Starting Comments migration (Collection Group)...');
    await migrateCollection(
      db.collectionGroup('comments'),
      'comments',
      globalMetrics.comments,
      globalMetrics.fieldsRemoved,
      allFailures,
      isExecute,
      batchSize
    );

    const duration = ((Date.now() - startTime) / 1000).toFixed(2);
    printReport(globalMetrics, duration, allFailures, isExecute);

    // Write failures to log
    if (allFailures.length > 0) {
      fs.writeFileSync(FAILURE_LOG_PATH, JSON.stringify(allFailures, null, 2));
      console.error(`❌ ${allFailures.length} failures recorded in ${FAILURE_LOG_PATH}`);
    } else if (isExecute) {
      if (fs.existsSync(FAILURE_LOG_PATH)) fs.unlinkSync(FAILURE_LOG_PATH);
    }

  } catch (err) {
    console.error('\nFATAL ERROR during migration:', err);
    process.exit(1);
  }
}

/**
 * Core migration engine for a specific query/collection.
 */
async function migrateCollection(queryRef, label, metrics, fieldMetrics, failures, isExecute, batchSize) {
  // Query for documents where author.avatarSeed exists.
  // Note: This relies on the COLLECTION_GROUP index for 'comments'.
  let query = queryRef.where('author.avatarSeed', '!=', null);
  let snapshot = await query.limit(batchSize).get();

  let batchCount = 0;

  while (!snapshot.empty) {
    batchCount++;
    console.log(`  Processing ${label} Batch #${batchCount} (${snapshot.size} docs)...`);

    const batch = isExecute ? db.batch() : null;
    let docsInBatch = 0;

    snapshot.forEach(doc => {
      metrics.scanned++;
      const data = doc.data();
      const author = data.author || {};

      try {
        if (!author.avatarSeed) {
          metrics.alreadyClean++;
          return;
        }

        // Transformation Logic
        const updates = {
          'author.sigilSeed': author.avatarSeed,
          'author.sigilColor': author.avatarColor || '#FFD700',
          'author.sigilConfig': {
            seed: author.avatarSeed,
            version: 3,
            palette: author.avatarConfig?.palette || 'AURORA',
            variant: author.avatarConfig?.variant || 'LIGHT',
            style: author.avatarConfig?.style || 'OUTLINE',
            weight: author.avatarConfig?.weight || 2.0
          },
          // Atomically delete legacy fields
          'author.avatarSeed': admin.firestore.FieldValue.delete(),
          'author.avatarColor': admin.firestore.FieldValue.delete(),
          'author.avatarConfig': admin.firestore.FieldValue.delete()
        };

        if (isExecute) {
          batch.update(doc.ref, updates);
        }

        docsInBatch++;
        metrics.migrated++;
        fieldMetrics.avatarSeed++;
        fieldMetrics.avatarColor++;
        fieldMetrics.avatarConfig++;

      } catch (err) {
        metrics.failed++;
        failures.push({
          id: doc.id,
          path: doc.ref.path,
          reason: err.message,
          timestamp: new Date().toISOString()
        });
      }
    });

    if (isExecute && docsInBatch > 0) {
      await batch.commit();
    }

    // Pagination: Query is resumable because we delete the criteria field ('author.avatarSeed')
    // For DRY RUN, we must startAfter the last doc since we didn't change anything.
    if (!isExecute) {
      const lastDoc = snapshot.docs[snapshot.size - 1];
      snapshot = await query.limit(batchSize).startAfter(lastDoc).get();
    } else {
      // Re-run the query. Since we deleted the criteria field, processed docs are excluded.
      snapshot = await query.limit(batchSize).get();
    }
  }
}

function printReport(metrics, duration, failures, isExecute) {
  console.log('\n=======================================');
  console.log('       MIGRATION SUMMARY REPORT        ');
  console.log('=======================================');
  console.log(`Status:          ${isExecute ? 'COMPLETED' : 'DRY RUN FINISHED'}`);
  console.log(`Duration:        ${duration}s`);
  console.log('---------------------------------------');
  console.log('ARTIFACTS:');
  console.log(`  Scanned:       ${metrics.artifacts.scanned}`);
  console.log(`  Migrated:      ${metrics.artifacts.migrated}`);
  console.log(`  Already Clean: ${metrics.artifacts.alreadyClean}`);
  console.log(`  Failed:        ${metrics.artifacts.failed}`);
  console.log('---------------------------------------');
  console.log('COMMENTS:');
  console.log(`  Scanned:       ${metrics.comments.scanned}`);
  console.log(`  Migrated:      ${metrics.comments.migrated}`);
  console.log(`  Already Clean: ${metrics.comments.alreadyClean}`);
  console.log(`  Failed:        ${metrics.comments.failed}`);
  console.log('---------------------------------------');
  console.log('LEGACY FIELDS REMOVED:');
  console.log(`  avatarSeed:    ${metrics.fieldsRemoved.avatarSeed}`);
  console.log(`  avatarColor:   ${metrics.fieldsRemoved.avatarColor}`);
  console.log(`  avatarConfig:  ${metrics.fieldsRemoved.avatarConfig}`);
  console.log('---------------------------------------');
  console.log(`TOTAL FAILURES:  ${failures.length}`);
  console.log('=======================================\n');
}

runMigration();
