/**
 * Storage URL Sanitization Script (v1.3.0)
 *
 * Purpose: Moves historical audio files from UID-bearing paths to sanitized paths
 * and updates Firestore references.
 *
 * PATH TRANSFORMATION:
 * From: artifacts/{UID}_{artifactId}.m4a
 * To:   artifacts/{artifactId}.m4a
 *
 * SAFETY INVARIANTS:
 * 1. Copy-then-Verify: Never delete original source during migration.
 * 2. Size Verification: Compare bytes after copy.
 * 3. Atomic URL Update: Only update Firestore after destination verification.
 */

const admin = require('firebase-admin');
const minimist = require('minimist');

const args = minimist(process.argv.slice(2), {
  boolean: ['execute', 'dry-run', 'help'],
  default: { 'dry-run': true }
});

if (args.help) {
  console.log(`
Usage: node sanitize_storage_urls.js [options]

Options:
  --execute       Perform Storage copies and Firestore updates.
  --dry-run       Inventory only, no mutations (Default).
  --help          Show this message.
  `);
  process.exit(0);
}

// Initialize Admin SDK
if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3',
    storageBucket: 'myartifact-555e3.firebasestorage.app'
  });
}

const db = admin.firestore();
const bucket = admin.storage().bucket();

async function run() {
  console.log('\n--- Storage URL Sanitization Execution ---');
  console.log(`Mode: ${args.execute ? 'EXECUTE (CAUTION)' : 'DRY RUN'}`);
  console.log('------------------------------------------\n');

  const stats = {
    totalArtifacts: 0,
    alreadySanitized: 0,
    legacyUrlsFound: 0,
    successfullyMigrated: 0,
    sourceMissing: 0,
    malformedUrls: 0,
    totalBytes: 0,
    failures: 0
  };

  try {
    const snapshot = await db.collection('artifacts')
      .where('migrationState', '==', 'COMPLETED')
      .get();

    stats.totalArtifacts = snapshot.size;
    console.log(`Scanning ${snapshot.size} sanitized artifacts...`);

    for (const doc of snapshot.docs) {
      const data = doc.data();
      const artifactId = doc.id;
      const audioUrl = data.audioUrl || '';

      if (!audioUrl) continue;

      const legacyPattern = /artifacts(?:[/]|%2F)([a-zA-Z0-9]{20,})_([a-zA-Z0-9-]{10,})\.m4a/;
      const match = audioUrl.match(legacyPattern);

      if (!match) {
        if (audioUrl.includes(`artifacts/${artifactId}.m4a`) || audioUrl.includes(`artifacts%2F${artifactId}.m4a`)) {
          stats.alreadySanitized++;
        } else {
          stats.malformedUrls++;
        }
        continue;
      }

      const uid = match[1];
      const matchedId = match[2];

      if (matchedId !== artifactId) {
        console.warn(`  [MISMATCH] ${artifactId} URL contains different ID: ${matchedId}`);
        stats.malformedUrls++;
        continue;
      }

      stats.legacyUrlsFound++;
      const sourcePath = `artifacts/${uid}_${artifactId}.m4a`;
      const destPath = `artifacts/${artifactId}.m4a`;

      const sourceFile = bucket.file(sourcePath);
      const [exists] = await sourceFile.exists();

      if (!exists) {
        console.error(`  [MISSING] ${artifactId}: Source file not found at ${sourcePath}`);
        stats.sourceMissing++;
        continue;
      }

      const [sourceMeta] = await sourceFile.getMetadata();
      const sourceSize = parseInt(sourceMeta.size);
      stats.totalBytes += sourceSize;

      if (args.execute) {
        const success = await executeMigration(doc, sourceFile, destPath, sourceSize, stats);
        if (success) stats.successfullyMigrated++;
        else stats.failures++;
      } else {
        console.log(`  [PENDING] ${artifactId}: ${sourceSize} bytes | ${sourcePath} -> ${destPath}`);
      }
    }

    printReport(stats);

  } catch (err) {
    console.error(`FATAL ERROR: ${err.message}`);
    process.exit(1);
  }
}

async function executeMigration(doc, sourceFile, destPath, sourceSize, stats) {
  const artifactId = doc.id;
  const destFile = bucket.file(destPath);

  try {
    // 1. Copy (Non-destructive)
    const [destExists] = await destFile.exists();
    if (!destExists) {
      await sourceFile.copy(destFile);
      console.log(`  [COPY] ${artifactId} copied to ${destPath}`);
    } else {
      console.log(`  [SKIP] ${artifactId} destination already exists at ${destPath}`);
    }

    // 2. Verify Destination
    const [destMeta] = await destFile.getMetadata();
    const destSize = parseInt(destMeta.size);

    if (destSize !== sourceSize) {
      throw new Error(`Size mismatch: Source ${sourceSize} vs Dest ${destSize}`);
    }

    // 3. Construct Public URL (Signed or Firebase pattern)
    // Note: We use the Firebase storage pattern for consistency
    const newAudioUrl = `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/artifacts%2F${artifactId}.m4a?alt=media`;

    // 4. Update Firestore
    await doc.ref.update({
      audioUrl: newAudioUrl,
      storageSanitizedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    console.log(`  [DONE] ${artifactId} Firestore URL updated.`);
    return true;

  } catch (err) {
    console.error(`  [FAIL] ${artifactId}: ${err.message}`);
    return false;
  }
}

function printReport(stats) {
  const mb = (stats.totalBytes / (1024 * 1024)).toFixed(2);

  console.log('\n--- Storage Migration Report ---');
  console.log(`Total Sanitzed Artifacts:  ${stats.totalArtifacts}`);
  console.log(`Already URL-Sanitized:     ${stats.alreadySanitized}`);
  console.log(`Legacy URLs Found:         ${stats.legacyUrlsFound}`);
  console.log(`Successfully Migrated:     ${stats.successfullyMigrated}`);
  console.log(`Failures:                  ${stats.failures}`);
  console.log(`Sources MISSING:           ${stats.sourceMissing}`);
  console.log(`Malformed/Mismatch URLs:   ${stats.malformedUrls}`);
  console.log('-----------------------------------------');
  console.log(`Total Data Volume:         ${mb} MB`);

  if (args.execute) {
    if (stats.failures === 0 && stats.successfullyMigrated === stats.legacyUrlsFound) {
      console.log(`Verdict: SUCCESS`);
    } else {
      console.log(`Verdict: PARTIAL SUCCESS / FAILURES FOUND`);
    }
  } else {
    console.log(`Verdict: DRY RUN COMPLETE`);
  }
  console.log('-----------------------------------------\n');
}

run();
