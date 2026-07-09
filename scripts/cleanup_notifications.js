/**
 * One-time Firestore cleanup utility for the Artifact project.
 *
 * Requirements:
 * - Use the Firebase Admin SDK.
 * - Find documents in 'notifications' where type == "REFLECTION".
 * - Dry-run mode by default.
 * - --execute flag to perform deletion with confirmation.
 *
 * Usage:
 * 1. Set GOOGLE_APPLICATION_CREDENTIALS environment variable.
 * 2. Run: node scripts/cleanup_notifications.js
 * 3. Run: node scripts/cleanup_notifications.js --execute
 */

const admin = require('firebase-admin');
const readline = require('readline');

// Initialize Admin SDK via GOOGLE_APPLICATION_CREDENTIALS
admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();

async function cleanup() {
  const args = process.argv.slice(2);
  const execute = args.includes('--execute');

  console.log('--- Artifact Firestore Cleanup Utility ---');
  if (!execute) {
    console.log('MODE: DRY RUN (No changes will be made)\n');
  } else {
    console.log('MODE: EXECUTE\n');
  }

  const notificationsRef = db.collection('notifications');
  const snapshot = await notificationsRef.where('type', '==', 'REFLECTION').get();

  if (snapshot.empty) {
    console.log('No REFLECTION notifications found.');
    return;
  }

  console.log(`Found ${snapshot.size} matching document(s):`);
  snapshot.forEach(doc => {
    const data = doc.data();
    const createdAt = data.createdAt ? (data.createdAt.toDate ? data.createdAt.toDate().toISOString() : data.createdAt) : 'N/A';
    console.log(`- ID: ${doc.id}`);
    console.log(`  Message:   "${data.message}"`);
    console.log(`  UserId:    ${data.userId}`);
    console.log(`  CreatedAt: ${createdAt}`);
    console.log('');
  });

  if (execute) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const answer = await new Promise(resolve => rl.question(`Are you sure you want to delete these ${snapshot.size} documents? (y/N) `, resolve));
    rl.close();

    if (answer.toLowerCase() === 'y') {
        console.log('\nStarting batch deletion...');
        const batch = db.batch();
        snapshot.forEach(doc => batch.delete(doc.ref));

        await batch.commit();
        console.log(`✅ Successfully deleted ${snapshot.size} notification(s).`);
    } else {
        console.log('\nDeletion cancelled.');
    }
  } else {
    console.log('Use --execute to perform the deletion.');
  }
}

cleanup().catch(err => {
    console.error('\n❌ Error during cleanup:');
    if (err.message.includes('Could not load the default credentials')) {
        console.error('Missing credentials. Please set the GOOGLE_APPLICATION_CREDENTIALS environment variable.');
        console.error('Example: $env:GOOGLE_APPLICATION_CREDENTIALS="C:\\path\\to\\key.json"');
    } else {
        console.error(err);
    }
    process.exit(1);
});
