const admin = require('firebase-admin');

process.env.FIRESTORE_EMULATOR_HOST = 'localhost:8080';

admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();

async function runTests() {
  const results = {};
  const uid = 'test-user-' + Date.now();
  const artifactId = 'test-artifact-' + Date.now();

  console.log(`Setting up test data... UID: ${uid}, ArtifactID: ${artifactId}`);

  // Setup Artifact
  await db.collection('artifacts').doc(artifactId).set({
    durationMs: 100000
  });

  // Case 1: Happy Path
  try {
    const engagementRef = db.collection('users').doc(uid).collection('engagement').doc(artifactId);
    await engagementRef.set({
      coverage: Buffer.alloc(3, 0xFF), // 24 bits
      hasReachedEnd: true,
      isCommentUnlocked: false
    });

    console.log('Waiting for Case 1 trigger...');
    await new Promise(resolve => setTimeout(resolve, 5000));
    let doc = await engagementRef.get();
    if (doc.data().isCommentUnlocked === true && doc.data().unlockTimestamp) {
      results['Case 1'] = 'PASS';
    } else {
      results['Case 1'] = 'FAIL';
      console.log('Case 1 Details:', doc.data());
    }
  } catch (e) {
    results['Case 1'] = 'FAIL';
    console.error('Case 1 Error:', e);
  }

  // Case 2: Below Threshold
  try {
    const uid2 = uid + '-case2';
    const engagementRef2 = db.collection('users').doc(uid2).collection('engagement').doc(artifactId);
    await engagementRef2.set({
      coverage: Buffer.from([0xFF, 0x7F, 0x00]), // 15 bits (75%)
      hasReachedEnd: true,
      isCommentUnlocked: false
    });

    console.log('Waiting for Case 2 trigger...');
    await new Promise(resolve => setTimeout(resolve, 3000));
    let doc = await engagementRef2.get();
    if (doc.data().isCommentUnlocked === false) {
      results['Case 2'] = 'PASS';
    } else {
      results['Case 2'] = 'FAIL';
    }
  } catch (e) {
    results['Case 2'] = 'FAIL';
  }

  // Case 3: Missing Artifact
  try {
    const artifactId3 = 'missing-artifact-' + Date.now();
    const engagementRef3 = db.collection('users').doc(uid).collection('engagement').doc(artifactId3);
    await engagementRef3.set({
      coverage: Buffer.alloc(3, 0xFF),
      hasReachedEnd: true,
      isCommentUnlocked: false
    });

    console.log('Waiting for Case 3 trigger...');
    await new Promise(resolve => setTimeout(resolve, 3000));
    let doc = await engagementRef3.get();
    if (doc.data().isCommentUnlocked === false) {
      results['Case 3'] = 'PASS';
    } else {
      results['Case 3'] = 'FAIL';
    }
  } catch (e) {
    results['Case 3'] = 'FAIL';
  }

  // Case 4: Invalid Coverage
  try {
    const uid4 = uid + '-case4';
    const engagementRef4 = db.collection('users').doc(uid4).collection('engagement').doc(artifactId);
    await engagementRef4.set({
      coverage: Buffer.alloc(100, 0xFF), // Sanity check trigger
      hasReachedEnd: true,
      isCommentUnlocked: false
    });

    console.log('Waiting for Case 4 trigger...');
    await new Promise(resolve => setTimeout(resolve, 3000));
    let doc = await engagementRef4.get();
    if (doc.data().isCommentUnlocked === false) {
      results['Case 4'] = 'PASS';
    } else {
      results['Case 4'] = 'FAIL';
    }
  } catch (e) {
    results['Case 4'] = 'FAIL';
  }

  // Case 5: Duplicate Trigger
  try {
    const engagementRef = db.collection('users').doc(uid).collection('engagement').doc(artifactId);
    // Already unlocked from Case 1
    const beforeDoc = await engagementRef.get();
    const beforeTime = beforeDoc.data().unlockTimestamp;

    await engagementRef.update({
      trigger: 'duplicate'
    });

    console.log('Waiting for Case 5 trigger...');
    await new Promise(resolve => setTimeout(resolve, 3000));
    let doc = await engagementRef.get();

    // Check if timestamp changed (it shouldn't due to loop prevention)
    if (doc.data().isCommentUnlocked === true &&
        doc.data().unlockTimestamp.isEqual(beforeTime)) {
      results['Case 5'] = 'PASS';
    } else {
      results['Case 5'] = 'FAIL';
      console.log('Case 5 Details: Before:', beforeTime, 'After:', doc.data().unlockTimestamp);
    }
  } catch (e) {
    results['Case 5'] = 'FAIL';
  }

  console.log('\n--- VERIFICATION REPORT ---');
  for (const [key, val] of Object.entries(results)) {
    console.log(`${key}: ${val}`);
  }

  const overall = Object.values(results).every(v => v === 'PASS') ? 'PASS' : 'FAIL';
  console.log(`\nOverall: ${overall}`);
}

runTests();
