/**
 * Persona Mapping Backfill Script (v1.0.0)
 *
 * Purpose: Ensures every active user's current anonymousId is registered in the
 * private persona_mapping registry. This is a prerequisite for the Historical UID Migration.
 *
 * Safety Invariants:
 * 1. ZERO mutations during --dry-run.
 * 2. Idempotent: Never overwrites existing mappings.
 * 3. Identity Reset Friendly: Does not touch historical mappings; only adds missing current ones.
 * 4. Verification-first: Mismatches are flagged for review, not corrected automatically.
 */

const admin = require('firebase-admin');
const minimist = require('minimist');

const args = minimist(process.argv.slice(2), {
  boolean: ['execute', 'dry-run', 'help'],
  default: { 'dry-run': true }
});

if (args.help) {
  console.log(`
Usage: node backfill_persona_mappings.js [options]

Options:
  --execute       Perform mutations (Firestore).
  --dry-run       Inventory only, no writes (Default).
  --help          Show this message.
  `);
  process.exit(0);
}

// Initialize Admin SDK
if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3'
  });
}

const db = admin.firestore();

async function run() {
  console.log('\n--- Persona Mapping Backfill ---');
  console.log(`Mode: ${args.execute ? 'EXECUTE' : 'DRY RUN'}`);
  console.log('--------------------------------\n');

  const stats = {
    totalUsers: 0,
    alreadyMapped: 0,
    created: 0,
    mismatched: 0,
    missingId: 0,
    reads: 0,
    writes: 0
  };

  try {
    const usersSnap = await db.collection('users').get();
    stats.reads += usersSnap.size;
    stats.totalUsers = usersSnap.size;

    console.log(`Discovered ${usersSnap.size} user profiles.`);

    for (const userDoc of usersSnap.docs) {
      const uid = userDoc.id;
      const data = userDoc.data();
      const anonId = data.anonymousId;

      if (!anonId || anonId.trim() === '') {
        console.warn(`[STALLED] User ${uid} has no anonymousId.`);
        stats.missingId++;
        continue;
      }

      const mappingRef = db.collection('persona_mapping').doc(anonId);
      const mappingSnap = await mappingRef.get();
      stats.reads++;

      if (mappingSnap.exists) {
        const mappingData = mappingSnap.data();
        if (mappingData.userId === uid) {
          stats.alreadyMapped++;
        } else {
          console.error(`[CONFLICT] Mapping mismatch: ${anonId} points to ${mappingData.userId}, but user is ${uid}`);
          stats.mismatched++;
        }
      } else {
        if (args.execute) {
          await mappingRef.set({
            userId: uid,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            backfilled: true
          });
          console.log(`[CREATED] Mapped ${anonId} -> ${uid}`);
          stats.created++;
          stats.writes++;
        } else {
          console.log(`[PENDING] Will map ${anonId} -> ${uid}`);
          stats.created++;
        }
      }
    }

    printReport(stats);

  } catch (err) {
    console.error(`FATAL ERROR: ${err.message}`);
    process.exit(1);
  }
}

function printReport(stats) {
  console.log('\n--- Backfill Report ---');
  console.log(`Total Users Processed:  ${stats.totalUsers}`);
  console.log(`Already Correctly Mapped: ${stats.alreadyMapped}`);
  console.log(`Newly Created/Pending:   ${stats.created}`);
  console.log(`Mismatched (Stalled):    ${stats.mismatched}`);
  console.log(`Missing ID (Stalled):    ${stats.missingId}`);
  console.log('-----------------------');
  console.log(`Firestore Reads:        ${stats.reads}`);
  console.log(`Firestore Writes:       ${stats.writes}`);

  if (stats.mismatched > 0 || stats.missingId > 0) {
    console.log('VERDICT: READY WITH EXCEPTIONS');
    console.log(`Reason: ${stats.mismatched + stats.missingId} records require manual review.`);
  } else if (stats.created > 0 && !args.execute) {
    console.log('VERDICT: DRY RUN SUCCESSFUL');
    console.log('Action: Run with --execute to commit changes.');
  } else {
    console.log('VERDICT: COMPLETED');
  }
  console.log('-----------------------\n');
}

run();
