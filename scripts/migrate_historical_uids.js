/**
 * Artifact Legacy UID Migration Script (v1.0.0)
 *
 * Purpose: Sanitizes historical Artifacts by removing stable Firebase UIDs from
 * public Firestore documents and Cloud Storage paths, moving to an indirect
 * ownership model via private registries.
 *
 * Safety Invariants:
 * 1. Duplication-first: Never delete original Storage objects.
 * 2. Registry-first: Never remove UID from Firestore until private registry is verified.
 * 3. Verify-then-Sanitize: Verify destination object exists and is readable before update.
 * 4. Idempotent: Every step checks for existing work.
 * 5. Rollback: metadata restoration from audit record.
 */

const admin = require('firebase-admin');
const minimist = require('minimist');

const args = minimist(process.argv.slice(2), {
  boolean: ['execute', 'dry-run', 'rollback', 'help'],
  string: ['batch-size'],
  default: { 'dry-run': true, 'batch-size': '50' }
});

if (args.help) {
  console.log(`
Usage: node migrate_historical_uids.js [options]

Options:
  --execute       Perform mutations (Firestore & Storage).
  --dry-run       Inventory only, no writes (Default).
  --rollback      Restore legacy metadata from audit record.
  --batch-size    Number of artifacts per batch (Default: 50).
  --help          Show this message.
  `);
  process.exit(0);
}

// Initialize Admin SDK
if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3',
    storageBucket: 'myartifact-555e3.appspot.com'
  });
}

const db = admin.firestore();
const bucket = admin.storage().bucket();

const STATES = {
  DISCOVERED: 'DISCOVERED',
  REGISTRY_BACKFILLED: 'REGISTRY_BACKFILLED',
  STORAGE_COPIED: 'STORAGE_COPIED',
  TRANSCRIPT_COPIED: 'TRANSCRIPT_COPIED',
  READ_VERIFIED: 'READ_VERIFIED',
  FIRESTORE_SANITIZED: 'FIRESTORE_SANITIZED',
  COMPLETED: 'COMPLETED',
  STALLED_OWNERSHIP_MISMATCH: 'STALLED_OWNERSHIP_MISMATCH',
  STALLED_SOURCE_MISSING: 'STALLED_SOURCE_MISSING',
  STALLED_PRE_REGISTRY_AMBIGUOUS: 'STALLED_PRE_REGISTRY_AMBIGUOUS'
};

async function run() {
  console.log('\n--- Artifact Legacy UID Migration ---');
  console.log(`Mode: ${args.execute ? 'EXECUTE' : (args.rollback ? 'ROLLBACK' : 'DRY RUN')}`);
  console.log(`Batch Size: ${args['batch-size']}`);
  console.log('--------------------------------------\n');

  if (args.rollback) {
    await performRollback();
  } else {
    await performMigration();
  }
}

async function performMigration() {
  const batchSize = parseInt(args['batch-size']);

  // 1. Inventory & Discovery
  // Query artifacts where userId exists
  const query = db.collection('artifacts').where('userId', '!=', null);
  const snapshot = await query.get();

  console.log(`Found ${snapshot.size} candidate legacy artifacts.`);

  if (args['dry-run']) {
    await runDryRun(snapshot);
    return;
  }

  if (!args.execute) {
    console.log('Use --execute to perform mutations.');
    return;
  }

  // 2. Processing
  let processed = 0;
  let skipped = 0;
  let failed = 0;
  let stalled = 0;

  for (const doc of snapshot.docs) {
    try {
      const artifactId = doc.id;
      const data = doc.data();
      const userId = data.userId;

      // Start or Resume state machine
      const auditRef = db.collection('migration_audit').doc(artifactId);
      const auditSnap = await auditRef.get();
      let audit = auditSnap.exists ? auditSnap.data() : {
        artifactId,
        state: STATES.DISCOVERED,
        originalUserId: userId,
        originalAudioUrl: data.audioUrl,
        originalTranscriptUrl: data.transcriptUrl || null,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      };

      if (audit.state === STATES.COMPLETED) {
        skipped++;
        continue;
      }

      console.log(`[${processed + 1}/${snapshot.size}] Migrating ${artifactId}...`);

      // STEP 1: Registry Backfill
      if (audit.state === STATES.DISCOVERED) {
        const registryRef = db.collection('users').doc(userId)
          .collection('private').doc('published_artifacts')
          .collection('artifacts').doc(artifactId);

        const regSnap = await registryRef.get();
        if (!regSnap.exists) {
          await registryRef.set({
            createdAt: data.createdAt || admin.firestore.FieldValue.serverTimestamp(),
            migrated: true
          });
          console.log(`  - Registry backfilled for ${userId}`);
        }
        audit.state = STATES.REGISTRY_BACKFILLED;
        await auditRef.set(audit);
      }

      // STEP 2: Storage Copy (Audio)
      if (audit.state === STATES.REGISTRY_BACKFILLED) {
        const sourcePath = `artifacts/${userId}_${artifactId}.m4a`;
        const destPath = `artifacts/${artifactId}.m4a`;

        const sourceFile = bucket.file(sourcePath);
        const [exists] = await sourceFile.exists();

        if (!exists) {
          console.warn(`  - Source missing: ${sourcePath}`);
          audit.state = STATES.STALLED_SOURCE_MISSING;
          await auditRef.set(audit);
          stalled++;
          continue;
        }

        const destFile = bucket.file(destPath);
        const [destExists] = await destFile.exists();

        if (!destExists) {
          await sourceFile.copy(destFile);
          console.log(`  - Audio copied to ${destPath}`);
        } else {
          console.log(`  - Audio copy already exists at ${destPath}`);
        }

        audit.state = STATES.STORAGE_COPIED;
        await auditRef.set(audit);
      }

      // STEP 3: Storage Copy (Transcript - Optional)
      if (audit.state === STATES.STORAGE_COPIED) {
        if (data.transcriptUrl) {
          const sourcePath = `transcripts/${userId}_${artifactId}.json`;
          const destPath = `transcripts/${artifactId}.json`;
          const sourceFile = bucket.file(sourcePath);
          const [exists] = await sourceFile.exists();

          if (exists) {
            const destFile = bucket.file(destPath);
            await sourceFile.copy(destFile);
            console.log(`  - Transcript copied to ${destPath}`);
          }
        }
        audit.state = STATES.TRANSCRIPT_COPIED;
        await auditRef.set(audit);
      }

      // STEP 4: Read Verification
      if (audit.state === STATES.TRANSCRIPT_COPIED) {
        const destFile = bucket.file(`artifacts/${artifactId}.m4a`);
        const [exists] = await destFile.exists();
        const [metadata] = await destFile.getMetadata();

        const sourceFile = bucket.file(`artifacts/${userId}_${artifactId}.m4a`);
        const [sourceMetadata] = await sourceFile.getMetadata();

        if (exists && metadata.size === sourceMetadata.size) {
          console.log(`  - Destination verified: ${metadata.size} bytes`);
          audit.state = STATES.READ_VERIFIED;
          await auditRef.set(audit);
        } else {
          throw new Error('Verification failed: Size mismatch or file missing after copy.');
        }
      }

      // STEP 5: Firestore Sanitization
      if (audit.state === STATES.READ_VERIFIED) {
        const newAudioUrl = audit.originalAudioUrl.replace(`${userId}_${artifactId}.m4a`, `${artifactId}.m4a`);
        const updates = {
          userId: admin.firestore.FieldValue.delete(),
          audioUrl: newAudioUrl,
          migratedAt: admin.firestore.FieldValue.serverTimestamp()
        };

        if (data.transcriptUrl) {
          updates.transcriptUrl = data.transcriptUrl.replace(`${userId}_${artifactId}.json`, `${artifactId}.json`);
        }

        await doc.ref.update(updates);
        console.log(`  - Firestore document sanitized (userId removed)`);
        audit.state = STATES.FIRESTORE_SANITIZED;
        await auditRef.set(audit);
      }

      // STEP 6: Completion
      if (audit.state === STATES.FIRESTORE_SANITIZED) {
        audit.state = STATES.COMPLETED;
        audit.completedAt = admin.firestore.FieldValue.serverTimestamp();
        await auditRef.set(audit);
        console.log(`  - COMPLETED`);
        processed++;
      }

    } catch (err) {
      console.error(`  - FAILED ${doc.id}: ${err.message}`);
      failed++;
    }
  }

  console.log('\n--- Migration Finished ---');
  console.log(`Processed: ${processed}`);
  console.log(`Skipped:   ${skipped}`);
  console.log(`Stalled:   ${stalled}`);
  console.log(`Failed:    ${failed}`);
}

async function performRollback() {
  const snapshot = await db.collection('migration_audit')
    .where('state', 'in', [STATES.FIRESTORE_SANITIZED, STATES.COMPLETED])
    .get();

  console.log(`Found ${snapshot.size} artifacts eligible for rollback.`);

  if (!args.execute) {
    console.log('Use --execute to perform mutations.');
    return;
  }

  let rolledBack = 0;
  for (const auditDoc of snapshot.docs) {
    const audit = auditDoc.data();
    const artifactRef = db.collection('artifacts').doc(audit.artifactId);

    await artifactRef.update({
      userId: audit.originalUserId,
      audioUrl: audit.originalAudioUrl,
      transcriptUrl: audit.originalTranscriptUrl,
      rolledBackAt: admin.firestore.FieldValue.serverTimestamp()
    });

    await auditDoc.ref.update({ state: STATES.DISCOVERED, rolledBack: true });
    console.log(`Rolled back ${audit.artifactId}`);
    rolledBack++;
  }
  console.log(`Rollback completed: ${rolledBack} artifacts.`);
}

async function runDryRun(snapshot) {
  let totalBytes = 0;
  let legacyCount = 0;
  let alreadySanitized = 0;
  let missingSource = 0;

  for (const doc of snapshot.docs) {
    const data = doc.data();
    const userId = data.userId;
    const artifactId = doc.id;

    if (userId) {
      legacyCount++;
      const sourcePath = `artifacts/${userId}_${artifactId}.m4a`;
      const sourceFile = bucket.file(sourcePath);

      try {
        const [exists] = await sourceFile.exists();
        if (exists) {
          const [metadata] = await sourceFile.getMetadata();
          totalBytes += parseInt(metadata.size);
        } else {
          missingSource++;
        }
      } catch (e) {
        console.warn(`Error checking ${sourcePath}: ${e.message}`);
      }
    } else {
      alreadySanitized++;
    }
  }

  const gb = (totalBytes / (1024 * 1024 * 1024)).toFixed(2);
  const estimatedCost = (legacyCount * 0.02 / 1000).toFixed(4); // Rough estimate

  console.log('\n--- Dry Run Report ---');
  console.log(`Legacy Artifacts:  ${legacyCount}`);
  console.log(`Already Sanitized: ${alreadySanitized}`);
  console.log(`Missing Sources:   ${missingSource}`);
  console.log(`Total Source Data: ${gb} GB`);
  console.log('-----------------------');
  console.log(`Estimated Firestore Ops: ${legacyCount * 5} writes`);
  console.log(`Estimated Storage Ops:   ${legacyCount} copies`);
  console.log(`Pricing Assumption: $0.05/10k Class A, $0.18/100k Firestore writes.`);
  console.log(`Estimated Op Cost: ~$${estimatedCost}`);
  console.log('-----------------------\n');
}

run();
