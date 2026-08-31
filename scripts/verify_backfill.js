const admin = require('firebase-admin');

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3'
  });
}

const db = admin.firestore();

async function verify() {
  console.log('--- Persona Mapping Verification ---');

  const expected = [
    { anonId: 'usr_25FB8', uid: 'SMhMSU5FaAZJ74zu5QGb1WCQEt13' },
    { anonId: 'usr_817C3', uid: 'nKCIAwfUdQhYqVBx1A2rZDCJXRh2' },
    { anonId: 'usr_AAF7B', uid: 'zHyh35uh5GboOtF7zGCWdDmPUg73' }
  ];

  for (const item of expected) {
    const mappingSnap = await db.collection('persona_mapping').doc(item.anonId).get();
    if (!mappingSnap.exists) {
      console.error(`[FAIL] Mapping missing for ${item.anonId}`);
      continue;
    }

    const mappingData = mappingSnap.data();
    if (mappingData.userId === item.uid) {
      console.log(`[PASS] ${item.anonId} -> ${item.uid} (Matches)`);
    } else {
      console.error(`[FAIL] ${item.anonId} points to ${mappingData.userId}, expected ${item.uid}`);
    }
  }
}

verify();
