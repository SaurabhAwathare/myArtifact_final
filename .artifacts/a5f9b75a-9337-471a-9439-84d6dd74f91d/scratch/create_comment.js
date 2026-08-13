const admin = require('firebase-admin');
admin.initializeApp({projectId: 'myartifact-555e3'});
const db = admin.firestore();
db.collection('artifacts').doc('c015a896-3e58-46dd-8f25-3464e708729e').collection('comments').doc('verification_test').set({
    creatorId: 'zcPz1GwJqzfjNcn005leIHSPtL13',
    text: 'Runtime verification',
    status: 'ACTIVE',
    createdAt: admin.firestore.FieldValue.serverTimestamp()
}).then(() => console.log('Comment created successfully'))
  .catch(err => console.error('Error creating comment:', err));
