/**
 * Final Cleanup Execution (v1.0.0)
 *
 * Purpose: Permanently deletes the 4 verified stalled legacy records
 * identified during the Social Graph Audit.
 */

const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3'
  });
}

const db = admin.firestore();

const TARGET_USERNAMES = ['ancient_trace_91', 'eerie moss', 'projackson190155'];
const TARGET_NOTIFICATION = '5fMW1g8R8Gj7Qoxnuim9';

async function cleanup() {
  console.log('--- Final Legacy Record Cleanup ---\n');

  let deletedCount = 0;

  // 1. Delete Usernames
  for (const name of TARGET_USERNAMES) {
    const ref = db.collection('usernames').doc(name);
    const snap = await ref.get();
    if (snap.exists) {
      await ref.delete();
      console.log(`  [DELETED] username: ${name}`);
      deletedCount++;
    } else {
      console.log(`  [SKIP] username: ${name} (not found)`);
    }
  }

  // 2. Delete Notification
  const notifRef = db.collection('notifications').doc(TARGET_NOTIFICATION);
  const notifSnap = await notifRef.get();
  if (notifSnap.exists) {
    await notifRef.delete();
    console.log(`  [DELETED] notification: ${TARGET_NOTIFICATION}`);
    deletedCount++;
  } else {
    console.log(`  [SKIP] notification: ${TARGET_NOTIFICATION} (not found)`);
  }

  console.log(`\nCleanup Complete. Total records deleted: ${deletedCount}`);

  if (deletedCount === 4) {
    console.log('VERDICT: SUCCESS (Exact match)');
  } else {
    console.log('VERDICT: PARTIAL SUCCESS (Verify existing state)');
  }
}

cleanup();
