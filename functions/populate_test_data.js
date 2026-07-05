const admin = require('firebase-admin');

admin.initializeApp({
  projectId: 'myartifact-555e3'
});

const db = admin.firestore();
const uid = 'rN7lKaC8ujTvjR7Gi0b0u4QfS632';
const targetId = 'Yjpao3PavUSoyL0LqJQlXn8kc5q1'; // Another user for resonance

async function populate() {
  console.log(`Populating data for user: ${uid}`);

  const batch = db.batch();

  // 1. User Document
  const userRef = db.collection('users').doc(uid);
  batch.set(userRef, {
    anonymousName: 'testuser_verification',
    anonymousSigil: '🕯️',
    avatarSeed: 'seed123',
    avatarColor: '#FF5733',
    isAnonymous: true,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    resonanceInCount: 0,
    resonanceOutCount: 1,
    followersCount: 0,
    followingCount: 1,
    artifactsCount: 1
  }, { merge: true });

  // 2. Username Reservation
  const usernameRef = db.collection('usernames').doc('testuser_verification');
  batch.set(usernameRef, { uid: uid });

  // 3. Published Artifact
  const artifactId = `art_${Date.now()}`;
  const artifactRef = db.collection('artifacts').doc(artifactId);
  batch.set(artifactRef, {
    userId: uid,
    title: 'Verification Artifact',
    audioUrl: `https://firebasestorage.googleapis.com/v0/b/myartifact-555e3.appspot.com/o/artifacts%2F${uid}_${artifactId}.m4a?alt=media`,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    status: 'ACTIVE',
    isPublic: true,
    reactionCount: 0,
    commentCount: 1
  });

  // 4. Reflection (Comment)
  const commentId = `comment_${Date.now()}`;
  const commentRef = db.collection('comments').doc(commentId);
  batch.set(commentRef, {
    artifactId: artifactId,
    authorId: uid,
    authorAnonymousName: 'testuser_verification',
    text: 'This is a test reflection for verification.',
    createdAt: admin.firestore.FieldValue.serverTimestamp()
  });

  // 5. Resonance (Following)
  // resonance_out: users/{uid}/resonance_out/{targetId}
  const resonanceOutRef = userRef.collection('resonance_out').doc(targetId);
  batch.set(resonanceOutRef, { createdAt: admin.firestore.FieldValue.serverTimestamp() });

  // resonance_in for target user: users/{targetId}/resonance_in/{uid}
  const resonanceInTargetRef = db.collection('users').doc(targetId).collection('resonance_in').doc(uid);
  batch.set(resonanceInTargetRef, { createdAt: admin.firestore.FieldValue.serverTimestamp() });

  // Update target user counters
  const targetUserRef = db.collection('users').doc(targetId);
  batch.update(targetUserRef, {
    resonanceInCount: admin.firestore.FieldValue.increment(1),
    followersCount: admin.firestore.FieldValue.increment(1)
  });

  // 6. Notifications
  const notificationId = `notif_${Date.now()}`;
  const notificationRef = db.collection('notifications').doc(notificationId);
  batch.set(notificationRef, {
    userId: uid,
    message: 'Welcome to the sanctuary!',
    type: 'SYSTEM',
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    isRead: false
  });

  // 7. Listening Session
  const sessionId = `session_${Date.now()}`;
  const sessionRef = db.collection('listening_sessions').doc(sessionId);
  batch.set(sessionRef, {
    userId: uid,
    artifactId: artifactId,
    durationMs: 30000,
    timestamp: admin.firestore.FieldValue.serverTimestamp()
  });

  await batch.commit();
  console.log('✅ Data population complete.');
}

populate().catch(console.error);
