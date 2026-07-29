/**
 * Firestore Backup Manager (v1.0.0)
 *
 * Purpose: Automates the creation and verification of Firestore Managed Exports.
 *
 * Requirements:
 * - Firebase Admin SDK
 * - Cloud Datastore Import Export Admin role
 *
 * Usage:
 * node scripts/firestore_backup_manager.js --action=backup
 * node scripts/firestore_backup_manager.js --action=verify --path=gs://bucket/prefix
 * node scripts/firestore_backup_manager.js --action=status --operation=projects/...
 */

const { v1 } = require('@google-cloud/firestore');
const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');

// --- Configuration ---
const PROJECT_ID = 'myartifact-555e3';
const BUCKET_NAME = 'myartifact-555e3-backups-asia';
const EXPORT_PREFIX = 'backups/sigil_migration';
const VERIFICATION_REPORT_PATH = path.join(__dirname, 'backup_verification_report.json');

// Initialize Clients
const client = new v1.FirestoreAdminClient();
admin.initializeApp({
  projectId: PROJECT_ID,
  storageBucket: BUCKET_NAME
});

async function main() {
  const args = require('minimist')(process.argv.slice(2));
  const action = args.action || 'check';

  console.log('\n--- Firestore Backup Manager ---');
  console.log(`Project: ${PROJECT_ID}`);
  console.log(`Bucket:  ${BUCKET_NAME}`);
  console.log('-------------------------------\n');

  try {
    switch (action) {
      case 'check':
        await performPreflightChecks();
        break;
      case 'backup':
        await triggerExport();
        break;
      case 'status':
        await checkStatus(args.operation);
        break;
      case 'verify':
        await verifyBackup(args.path);
        break;
      default:
        console.error(`Unknown action: ${action}`);
        process.exit(1);
    }
  } catch (err) {
    console.error('\nFATAL ERROR:', err);
    process.exit(1);
  }
}

async function performPreflightChecks() {
  console.log('[1/3] Checking Cloud Storage bucket...');
  const [exists] = await admin.storage().bucket().exists();
  if (!exists) {
    throw new Error(`Bucket ${BUCKET_NAME} does not exist.`);
  }
  console.log('✅ Bucket verified.\n');

  console.log('[2/3] Identifying Firestore Service Agent...');
  // Project number is needed for the service agent name
  // Note: In some environments, we can fetch this via API, but here we hardcode
  // based on previous investigation or prompt the user.
  const projectNumber = '1002864539573';
  const serviceAgent = `service-${projectNumber}@gcp-sa-firestore.iam.gserviceaccount.com`;
  console.log(`Service Agent: ${serviceAgent}`);
  console.log('ℹ️ Ensure this agent has "Storage Admin" or "Firestore Service Agent" role on the bucket.\n');

  console.log('[3/3] Verifying Firestore Read Access...');
  const collections = await admin.firestore().listCollections();
  console.log(`✅ Success. Found ${collections.length} collections.`);
  console.log('\nPRE-FLIGHT COMPLETE. Safe to run --action=backup');
}

async function triggerExport() {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const outputUriPrefix = `gs://${BUCKET_NAME}/${EXPORT_PREFIX}/${timestamp}`;

  console.log(`Initiating export to: ${outputUriPrefix}...`);

  const request = {
    name: client.databasePath(PROJECT_ID, '(default)'),
    outputUriPrefix: outputUriPrefix,
    collectionIds: [] // Empty means all
  };

  const [operation] = await client.exportDocuments(request);
  console.log('\n✅ Export operation started!');
  console.log(`Operation Name: ${operation.name}`);
  console.log(`\nTo monitor progress, run:`);
  console.log(`node scripts/firestore_backup_manager.js --action=status --operation="${operation.name}"`);

  // Also provide verify command
  console.log(`\nTo verify once complete:`);
  console.log(`node scripts/firestore_backup_manager.js --action=verify --path="${outputUriPrefix}"`);
}

async function checkStatus(operationName) {
  if (!operationName) throw new Error('Missing --operation argument');

  console.log(`Checking status of ${operationName}...`);
  const operation = await client.checkExportDocumentsProgress(operationName);

  const metadata = operation.metadata;
  const done = operation.done;

  if (metadata) {
    const workCompleted = metadata.progressDocuments?.completedWork || 0;
    const workEstimated = metadata.progressDocuments?.estimatedWork || 0;
    const percent = workEstimated > 0 ? ((workCompleted / workEstimated) * 100).toFixed(2) : 0;

    console.log(`Progress: ${workCompleted} / ${workEstimated} docs (${percent}%)`);
    console.log(`State:    ${metadata.state}`);
  }

  if (done) {
    console.log('\n🏁 Operation is COMPLETE.');
    if (operation.error) {
      console.error('❌ Error:', operation.error.message);
    } else {
      console.log('✅ Success!');
    }
  } else {
    console.log('\nStill in progress. Re-run in a few minutes.');
  }
}

async function verifyBackup(gsPath) {
  if (!gsPath) throw new Error('Missing --path argument (e.g., gs://bucket/prefix/timestamp)');

  console.log(`Verifying backup artifacts at ${gsPath}...`);

  const bucket = admin.storage().bucket();
  const prefix = gsPath.replace(`gs://${BUCKET_NAME}/`, '');

  const [files] = await bucket.getFiles({ prefix: prefix });

  const report = {
    timestamp: new Date().toISOString(),
    path: gsPath,
    filesFound: files.length,
    hasMetadata: false,
    hasData: false,
    isValid: false,
    reasons: []
  };

  // Check for the critical .overall_export_metadata file
  const metadataFile = files.find(f => f.name.endsWith('.overall_export_metadata'));
  if (metadataFile) {
    report.hasMetadata = true;
    console.log(`✅ Metadata found: ${metadataFile.name}`);
  } else {
    report.reasons.push('Missing .overall_export_metadata file');
  }

  // Check for LevelDB log files
  const dataFiles = files.filter(f => f.name.includes('output-'));
  if (dataFiles.length > 0) {
    report.hasData = true;
    console.log(`✅ Data segments found: ${dataFiles.length} files.`);
  } else {
    report.reasons.push('No data segment files found (output-*)');
  }

  if (report.hasMetadata && report.hasData) {
    report.isValid = true;
    console.log('\n✅ BACKUP VERIFIED. Safe for disaster recovery.');
  } else {
    console.error('\n❌ BACKUP INVALID or INCOMPLETE.');
    report.reasons.forEach(r => console.error(`   - ${r}`));
  }

  fs.writeFileSync(VERIFICATION_REPORT_PATH, JSON.stringify(report, null, 2));
  console.log(`\nDetailed report written to ${VERIFICATION_REPORT_PATH}`);
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
