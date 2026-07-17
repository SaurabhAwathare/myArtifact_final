const admin = require('firebase-admin');

process.env.FIRESTORE_EMULATOR_HOST = 'localhost:8080';

admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();

async function waitForCondition(ref, condition, timeoutMs = 10000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const doc = await ref.get();
    if (condition(doc.data())) {
      return doc;
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  return await ref.get();
}

async function runTests() {
  const results = {};
  const runId = Date.now();
  const uid = 'user-' + runId;
  const artifactId = 'art-' + runId;

  console.log(`RUN ID: ${runId}`);

  // Setup Artifact
  await db.collection('artifacts').doc(artifactId).set({
    durationMs: 100000
  });

  // Case 1: Happy Path
  console.log('Case 1: Happy Path...');
  const engagementRef = db.collection('users').doc(uid).collection('engagement').doc(artifactId);
  await engagementRef.set({
    coverage: Buffer.alloc(3, 0xFF), // 24 bits
    hasReachedEnd: true,
    isCommentUnlocked: false
  });

  let doc1 = await waitForCondition(engagementRef, d => d.isCommentUnlocked === true);
  if (doc1.data().isCommentUnlocked === true && doc1.data().unlockTimestamp) {
    results['Case 1'] = 'PASS';
  } else {
    results['Case 1'] = 'FAIL';
  }

  // Case 2: Below Threshold
  console.log('Case 2: Below Threshold...');
  const uid2 = uid + '-case2';
  const engagementRef2 = db.collection('users').doc(uid2).collection('engagement').doc(artifactId);
  await engagementRef2.set({
    coverage: Buffer.from([0xFF, 0x7F, 0x00]), // 15 bits (75%)
    hasReachedEnd: true,
    isCommentUnlocked: false
  });
  // Wait enough time to be sure it DOESN'T unlock
  await new Promise(resolve => setTimeout(resolve, 3000));
  let doc2 = await engagementRef2.get();
  results['Case 2'] = (doc2.data().isCommentUnlocked === false) ? 'PASS' : 'FAIL';

  // Case 3: Missing Artifact
  console.log('Case 3: Missing Artifact...');
  const artifactId3 = 'missing-' + runId;
  const engagementRef3 = db.collection('users').doc(uid).collection('engagement').doc(artifactId3);
  await engagementRef3.set({
    coverage: Buffer.alloc(3, 0xFF),
    hasReachedEnd: true,
    isCommentUnlocked: false
  });
  await new Promise(resolve => setTimeout(resolve, 3000));
  let doc3 = await engagementRef3.get();
  results['Case 3'] = (doc3.data().isCommentUnlocked === false) ? 'PASS' : 'FAIL';

  // Case 4: Invalid Coverage
  console.log('Case 4: Invalid Coverage...');
  const uid4 = uid + '-case4';
  const engagementRef4 = db.collection('users').doc(uid4).collection('engagement').doc(artifactId);
  await engagementRef4.set({
    coverage: Buffer.alloc(100, 0xFF), // Sanity check trigger
    hasReachedEnd: true,
    isCommentUnlocked: false
  });
  await new Promise(resolve => setTimeout(resolve, 3000));
  let doc4 = await engagementRef4.get();
  results['Case 4'] = (doc4.data().isCommentUnlocked === false) ? 'PASS' : 'FAIL';

  // Case 5: Duplicate Trigger
  console.log('Case 5: Duplicate Trigger...');
  if (results['Case 1'] === 'PASS') {
    const beforeTime = doc1.data().unlockTimestamp;
    await engagementRef.update({ trigger: 'dup' });
    await new Promise(resolve => setTimeout(resolve, 3000));
    let doc5 = await engagementRef.get();
    if (doc5.data().isCommentUnlocked === true &&
        doc5.data().unlockTimestamp.isEqual(beforeTime)) {
      results['Case 5'] = 'PASS';
    } else {
      results['Case 5'] = 'FAIL';
    }
  } else {
    results['Case 5'] = 'SKIPPED (Case 1 failed)';
  }

  console.log('\n--- VERIFICATION REPORT ---');
  for (const [key, val] of Object.entries(results)) {
    console.log(`${key}: ${val}`);
  }
  const overall = Object.values(results).every(v => v === 'PASS') ? 'PASS' : 'FAIL';
  console.log(`\nOverall: ${overall}`);
}

runTests();
