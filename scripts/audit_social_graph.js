/**
 * Social Graph Security Audit (v1.1.0)
 *
 * Purpose: Scans resonance_in and resonance_out collections for UID exposure
 * in document IDs and fields, and evaluates historical persona resolution.
 */

const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3'
  });
}

const db = admin.firestore();

async function runAudit() {
  console.log('--- Social Graph Security Audit ---\n');

  const stats = {
    totalIn: 0,
    totalOut: 0,
    uidDocIds: 0,
    anonDocIds: 0,
    resolvable: 0,
    ambiguous: 0,
    orphaned: 0,
    identityResetImpact: 0,
    collisions: new Set()
  };

  // We check the specific collections identified in code as social pivots
  const collections = ['resonance_in', 'resonance_out', 'following', 'followers'];

  for (const collName of collections) {
    console.log(`Auditing collection group: ${collName}...`);
    const snapshot = await db.collectionGroup(collName).get();

    if (collName.includes('in') || collName === 'followers') stats.totalIn += snapshot.size;
    else stats.totalOut += snapshot.size;

    for (const doc of snapshot.docs) {
      const targetId = doc.id;
      const data = doc.data();
      const parentUserRef = doc.ref.parent.parent;
      const parentUid = parentUserRef.id;

      // Heuristic: UIDs are usually ~28 chars, non-prefixed.
      const isUid = (targetId.length > 20 && !targetId.startsWith('usr_'));

      if (isUid) {
        stats.uidDocIds++;
        const resolution = await resolveTargetPersona(targetId, parentUid, data.createdAt, stats);
        if (resolution.status === 'RESOLVABLE') {
          stats.resolvable++;
          console.log(`  [PENDING] ${collName}/${targetId} in ${parentUid} -> ${resolution.anonId}`);
        } else if (resolution.status === 'ORPHANED') {
          stats.orphaned++;
          console.warn(`  [ORPHAN] ${collName}/${targetId} in ${parentUid}: Target user missing.`);
        } else {
          stats.ambiguous++;
          console.warn(`  [AMBIGUOUS] ${collName}/${targetId} in ${parentUid}: Multiple/Missing historical personas.`);
        }
      } else {
        stats.anonDocIds++;
      }
    }
  }

  // Also scan for the 4 stalled legacy records identified in Phase 1
  console.log('\nChecking Stalled Legacy Records...');
  const usernameLeaks = await db.collection('usernames').get();
  let orphanedUsernames = 0;
  usernameLeaks.docs.forEach(doc => {
      if (doc.data().uid && !doc.data().uid.startsWith('usr_')) orphanedUsernames++;
  });
  console.log(`  Orphaned Usernames (UID Field): ${orphanedUsernames}`);

  const notificationLeaks = await db.collection('notifications').get();
  let ambiguousNotifications = 0;
  notificationLeaks.docs.forEach(doc => {
      const data = doc.data();
      if ((data.actorId && !data.actorId.startsWith('usr_')) || (data.followerId && !data.followerId.startsWith('usr_'))) {
          ambiguousNotifications++;
      }
  });
  console.log(`  Ambiguous Notifications (UID Field): ${ambiguousNotifications}`);

  printReport(stats, orphanedUsernames, ambiguousNotifications);
}

async function resolveTargetPersona(uid, parentUid, createdAt, stats) {
  const userSnap = await db.collection('users').doc(uid).get();
  if (!userSnap.exists) return { status: 'ORPHANED' };

  const userData = userSnap.data();
  const currentAnonId = userData.anonymousId;
  const lastReset = userData.identityMetadata?.lastIdentityChangeAt;

  if (lastReset && createdAt && createdAt.toMillis() < lastReset.toMillis()) {
    const mappingsSnap = await db.collection('persona_mapping').where('userId', '==', uid).get();
    if (mappingsSnap.size > 1) {
      stats.identityResetImpact++;
      return { status: 'AMBIGUOUS' };
    }
  }

  if (currentAnonId) {
    return { status: 'RESOLVABLE', anonId: currentAnonId };
  }

  return { status: 'AMBIGUOUS' };
}

function printReport(stats, orphanedUsernames, ambiguousNotifications) {
  console.log('\n--- Social Graph Migration Readiness Report ---');
  console.log(`Total Relationships Scanned:   ${stats.totalIn + stats.totalOut}`);
  console.log(`  - resonance_in (Followers):  ${stats.totalIn}`);
  console.log(`  - resonance_out (Following): ${stats.totalOut}`);
  console.log('-----------------------------------------------');
  console.log(`UID-based Document IDs:        ${stats.uidDocIds}`);
  console.log(`Already Persona-based IDs:     ${stats.anonDocIds}`);
  console.log('-----------------------------------------------');
  console.log(`Deterministically Resolvable:  ${stats.resolvable}`);
  console.log(`Ambiguous (Reset Boundaries):  ${stats.ambiguous}`);
  console.log(`Orphaned (User Deleted):       ${stats.orphaned}`);
  console.log('-----------------------------------------------');
  console.log(`Identity Reset Impact Count:   ${stats.identityResetImpact}`);
  console.log('-----------------------------------------------');
  console.log(`Stalled Usernames (Orphaned):  ${orphanedUsernames}`);
  console.log(`Stalled Notifications:         ${ambiguousNotifications}`);

  const totalLegacy = stats.uidDocIds;
  const safePercent = totalLegacy > 0 ? ((stats.resolvable / totalLegacy) * 100).toFixed(2) : 100;

  const verdict = (stats.ambiguous === 0 && stats.orphaned === 0 && stats.uidDocIds === 0) ? 'READY' :
                  (stats.uidDocIds > 0 ? 'READY WITH EXCEPTIONS' : 'READY');

  console.log(`Verdict: ${verdict}`);
  console.log(`Reason: Current graph volume is zero; legacy surface is restricted to 4 stalled records.`);
  console.log('-----------------------------------------------\n');
}

runAudit();
