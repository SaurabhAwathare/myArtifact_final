const admin = require('firebase-admin');

admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();

async function test() {
  const uid = '25grMFg4WjWDUaII6T4VWywjCHv2';
  const userRef = db.collection('users').doc(uid);

  try {
    const doc = await userRef.get();
    console.log('Document exists:', doc.exists);
    if (doc.exists) {
      console.log('Document data:', doc.data());
    }
  } catch (e) {
    console.error('Error getting document:', e);
  }
}

test();
