const admin = require('firebase-admin');

admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();

async function test() {
  const uid = '25grMFg4WjWDUaII6T4VWywjCHv2';

  try {
    const userDoc = await db.collection('users').doc(uid).get();
    console.log('--- USER DOCUMENT ---');
    if (userDoc.exists) {
      console.log(JSON.stringify(userDoc.data(), null, 2));
    } else {
      console.log('User document not found');
    }

    const settingsDoc = await db.collection('users').doc(uid).collection('private').doc('settings').get();
    console.log('\n--- PRIVATE SETTINGS ---');
    if (settingsDoc.exists) {
      console.log(JSON.stringify(settingsDoc.data(), null, 2));
    } else {
      console.log('Settings document not found');
    }
  } catch (e) {
    console.error('Error:', e);
  }
}

test();
