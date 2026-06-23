const admin = require('firebase-admin');
const fs = require('fs');

if (!admin.apps.length) {
  admin.initializeApp({
    projectId: 'myartifact-555e3'
  });
}

const db = admin.firestore();
const uid = '25grMFg4WjWDUaII6T4VWywjCHv2';

async function dump() {
  console.log('Starting dump...');
  try {
    const userDoc = await db.collection('users').doc(uid).get();
    const settingsDoc = await db.collection('users').doc(uid).collection('private').doc('settings').get();

    const data = {
      user: userDoc.data(),
      settings: settingsDoc.data()
    };

    fs.writeFileSync('dump.json', JSON.stringify(data, null, 2));
    console.log('DUMP_SUCCESS');
  } catch (e) {
    console.error('DUMP_ERROR:', e);
  }
  process.exit(0);
}

dump();
