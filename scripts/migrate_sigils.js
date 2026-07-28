/**
 * Artifact Sigil Migration Script (v1.0.0)
 *
 * Purpose: Restores visual identity to historical Artifacts by migrating legacy authorAvatar*
 * fields to authorSigil* fields and normalizing sigilConfig to Version 3.
 *
 * Safeguards:
 * - Default Dry-Run mode.
 * - Explicit --execute flag required for writes.
 * - Interactive confirmation prompt ("MIGRATE").
 * - Atomic per-document updates.
 * - Idempotent and resumable logic.
 * - Batching (default 500) with independent commits.
 * - Post-migration sampling verification.
 *
 * Usage:
 * node scripts/migrate_sigils.js [--execute] [--batch-size 500] [--sample-size 50] [--yes]
 */

const admin = require('firebase-admin');
const fs = require('fs');
const readline = require('readline');

// --- Configuration ---
const FAILURE_LOG_PATH = 'scripts/migration_failures.log.json';
const DEFAULT_BATCH_SIZE = 500;
const DEFAULT_SAMPLE_SIZE = 50;

// Initialize Admin SDK
admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();

async function runMigration() {
  const args = process.argv.slice(2);
  const isExecute = args.includes('--execute');
  const skipConfirm = args.includes('--yes');
  const batchSize = parseInt(args.find(a => a.startsWith('--batch-size'))?.split('=')[1] || DEFAULT_BATCH_SIZE);
  const sampleSize = parseInt(args.find(a => a.startsWith('--sample-size'))?.split('=')[1] || DEFAULT_SAMPLE_SIZE);

  console.log('\n--- Artifact Sigil Migration ---');
  console.log(`Mode: ${isExecute ? 'EXECUTE' : 'DRY RUN'}`);
  console.log(`Batch Size: ${batchSize}`);
  console.log(`Verification Sample Size: ${sampleSize}`);
  console.log('-------------------------------\n');

  if (isExecute && !skipConfirm) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const answer = await new Promise(resolve => rl.question('⚠️ CAUTION: You are about to modify historical artifacts. Type "MIGRATE" to continue: ', resolve));
    rl.close();
    if (answer !== 'MIGRATE') {
      console.log('Migration cancelled by operator.');
      process.exit(0);
    }
  }

  const startTime = Date.now();
  const metrics = {
    scanned: 0,
    migrated: 0,
    alreadyMigrated: 0,
    skipped: 0,
    failed: 0,
    batches: 0
  };
  const failures = [];
  const candidateSample = [];
  const migratedIds = [];
  const MAX_SAMPLE_SIZE = 10;

  try {
    // 1. Query for documents needing migration
    // We target documents where author.avatarSeed exists.
    const artifactsRef = db.collection('artifacts');
    const query = artifactsRef.where('author.avatarSeed', '!=', null);

    let snapshot = await query.limit(batchSize).get();

    while (!snapshot.empty) {
      metrics.batches++;
      console.log(`Processing Batch #${metrics.batches} (${snapshot.size} docs)...`);

      const batch = isExecute ? db.batch() : null;
      let docsInBatch = 0;

      snapshot.forEach(doc => {
        metrics.scanned++;
        const data = doc.data();
        const author = data.author || {};

        try {
          // Validation: Ensure we have source data to migrate from
          if (!author.avatarSeed) {
            metrics.alreadyMigrated++;
            return;
          }

          // Sample collection
          if (candidateSample.length < MAX_SAMPLE_SIZE) {
            candidateSample.push(doc.id);
          }

          migratedIds.push(doc.id);

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
        } catch (err) {
          metrics.failed++;
          failures.push({
            id: doc.id,
            reason: err.message,
            timestamp: new Date().toISOString(),
            retryEligible: true
          });
        }
      });

      if (isExecute && docsInBatch > 0) {
        await batch.commit();
      }

      // Fetch next page (Query is naturally resumable because we delete the criteria field)
      // For DRY RUN, we need to manually paginate using startAfter because we don't delete fields
      if (!isExecute) {
        const lastDoc = snapshot.docs[snapshot.size - 1];
        snapshot = await query.limit(batchSize).startAfter(lastDoc).get();
      } else {
        snapshot = await query.limit(batchSize).get();
      }
    }

    const duration = ((Date.now() - startTime) / 1000).toFixed(2);
    console.log('\n--- Migration Results ---');
    console.log(`Duration:        ${duration}s`);
    console.log(`Docs Scanned:    ${metrics.scanned}`);
    console.log(`Docs Migrated:   ${metrics.migrated} (Candidates)`);
    console.log(`Already Clean:   ${metrics.alreadyMigrated}`);
    console.log(`Failed:          ${metrics.failed}`);
    console.log(`Batch Count:     ${metrics.batches}`);

    if (candidateSample.length > 0 && !isExecute) {
      console.log('\nSample Candidate IDs:');
      candidateSample.forEach(id => console.log(`- ${id}`));
    }
    console.log('--------------------------\n');

    // Write failures to log
    if (failures.length > 0) {
      fs.writeFileSync(FAILURE_LOG_PATH, JSON.stringify(failures, null, 2));
      console.error(`❌ ${failures.length} failures recorded in ${FAILURE_LOG_PATH}`);
    } else if (isExecute) {
      // Clear failure log on successful execution
      if (fs.existsSync(FAILURE_LOG_PATH)) fs.unlinkSync(FAILURE_LOG_PATH);
    }

    // 2. Post-Migration Verification Pass (All Migrated Documents)
    if (isExecute && metrics.migrated > 0) {
      console.log(`Running Post-Migration Verification on ${migratedIds.length} docs...`);
      let verifiedCount = 0;
      let errorCount = 0;

      // Verification in small chunks to avoid memory/rate limits
      const verificationChunks = [];
      for (let i = 0; i < migratedIds.length; i += 10) {
          verificationChunks.push(migratedIds.slice(i, i + 10));
      }

      for (const chunkIds of verificationChunks) {
          const chunkSnapshot = await Promise.all(chunkIds.map(id => artifactsRef.doc(id).get()));

          chunkSnapshot.forEach(doc => {
            const data = doc.data();
            const author = data.author || {};
            const isVerified = author.sigilSeed &&
                               author.sigilColor &&
                               author.sigilConfig?.version === 3 &&
                               author.avatarSeed === undefined &&
                               author.avatarColor === undefined &&
                               author.avatarConfig === undefined;

            if (isVerified) {
              verifiedCount++;
            } else {
              errorCount++;
              console.warn(`⚠️ Verification mismatch in doc ${doc.id}`);
            }
          });
      }

      console.log(`Verification Results: ${verifiedCount} passed, ${errorCount} suspicious.`);
      if (errorCount === 0) {
        console.log('✅ Post-migration verification successful.');
      } else {
        console.error('❌ Post-migration verification FAILED for some documents.');
        process.exit(1);
      }
    }

  } catch (err) {
    console.error('\nFATAL ERROR during migration:', err);
    process.exit(1);
  }
}

runMigration();
