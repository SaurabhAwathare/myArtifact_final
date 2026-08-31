/**
 * Post-Migration Security Audit (v1.0.0)
 *
 * Purpose: Scans public-facing Firestore collections and Storage metadata for
 * remaining raw Firebase UIDs to ensure the Responsible Anonymity boundary is closed.
 */

const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3',
    storageBucket: 'myartifact-555e3.appspot.com'
  });
}

const db = admin.firestore();
const bucket = admin.storage().bucket();

const PUBLIC_COLLECTIONS = [
  'artifacts',
  'comments', // collection group
  'artifact_reactions',
  'profiles',
  'usernames'
];

const SENSITIVE_FIELDS = ['userId', 'creatorId', 'uid', 'actorId', 'followerId', 'reporterId'];

async function runAudit() {
  console.log('--- Post-Migration Security Audit ---\n');

  // 1. Scan Public Firestore Collections
  for (const collName of PUBLIC_COLLECTIONS) {
    console.log(`Auditing collection: ${collName}...`);
    let query;
    if (collName === 'comments') {
      query = db.collectionGroup(collName);
    } else {
      query = db.collection(collName);
    }

    const snapshot = await query.get();
    let leaks = 0;

    snapshot.docs.forEach(doc => {
      const data = doc.data();
      SENSITIVE_FIELDS.forEach(field => {
        if (data[field] && typeof data[field] === 'string' && data[field].length > 20 && !data[field].startsWith('usr_')) {
          // Heuristic: UIDs are usually ~28 chars, non-prefixed.
          console.warn(`  [LEAK] ${collName}/${doc.id} contains raw field: ${field}`);
          leaks++;
        }
      });

      // 2. Check Storage URLs in artifacts
      if (collName === 'artifacts') {
        const audioUrl = data.audioUrl || '';
        if (audioUrl.includes('firebasestorage') && audioUrl.match(/[a-zA-Z0-9]{20,}_/)) {
            // Found UID pattern in filename
            console.warn(`  [STORAGE_LEAK] ${collName}/${doc.id} audioUrl contains UID pattern.`);
            leaks++;
        }
      }
    });

    if (leaks === 0) console.log(`  [OK] No raw UIDs found in ${snapshot.size} records.`);
  }

  // 3. Inspect Stalled Records
  console.log('\nInspecting Stalled Records...');
  const auditSnap = await db.collection('migration_audit').where('state', '==', 'STALLED_ORPHANED').get();
  console.log(`  Orphaned Usernames: ${auditSnap.size}`);

  const ambiguousSnap = await db.collection('migration_audit').where('state', '==', 'STALLED_AMBIGUOUS').get();
  console.log(`  Ambiguous Records: ${ambiguousSnap.size}`);

  console.log('\nAudit Complete.');
}

runAudit();
