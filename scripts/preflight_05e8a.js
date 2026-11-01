const fs = require('fs');
const path = require('path');
const os = require('os');

async function main() {
  const configPath = path.join(os.homedir(), '.config', 'configstore', 'firebase-tools.json');
  if (!fs.existsSync(configPath)) {
    throw new Error('Configstore not found at: ' + configPath);
  }

  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  const accessToken = config.tokens?.access_token;

  if (!accessToken) {
    throw new Error('No access token found in firebase-tools.json');
  }

  const projectId = 'myartifact-staging';
  const listenerUid = 'LVia4YjkE3c8fk82Y6FnOkBMFfi1';
  const artifactId = 'art_staging_comment_target_001';
  const ownerUid = 'usr_owner_staging_seed_001';
  const baseUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;

  const headers = {
    'Authorization': `Bearer ${accessToken}`,
    'Content-Type': 'application/json'
  };

  console.log(`======================================================`);
  console.log(` TEST-STATE-05E.8A — READ-ONLY PREFLIGHT VERIFICATION`);
  console.log(` Project: ${projectId}`);
  console.log(` Listener UID: ${listenerUid}`);
  console.log(` Artifact ID: ${artifactId}`);
  console.log(` Owner UID: ${ownerUid}`);
  console.log(`======================================================\n`);

  // 1. Engagement document
  console.log(`--- 1. Checking Engagement Document ---`);
  const engUrl = `${baseUrl}/users/${listenerUid}/engagement/${artifactId}`;
  const engRes = await fetch(engUrl, { headers });
  console.log(`Engagement doc status: ${engRes.status} ${engRes.statusText}`);
  if (engRes.status === 200) {
    const doc = await engRes.json();
    console.log(`Engagement doc name: ${doc.name}`);
    console.log(`Fields: ${JSON.stringify(doc.fields, null, 2)}`);
  } else {
    console.error(`Engagement body: ${await engRes.text()}`);
  }

  // 2. Artifact document
  console.log(`\n--- 2. Checking Artifact Document ---`);
  const artUrl = `${baseUrl}/artifacts/${artifactId}`;
  const artRes = await fetch(artUrl, { headers });
  console.log(`Artifact doc status: ${artRes.status} ${artRes.statusText}`);
  if (artRes.status === 200) {
    const doc = await artRes.json();
    console.log(`Artifact doc name: ${doc.name}`);
    console.log(`Fields: ${JSON.stringify(doc.fields, null, 2)}`);
  } else {
    console.error(`Artifact body: ${await artRes.text()}`);
  }

  // 3. Existing comments under /artifacts/{artifactId}/comments
  console.log(`\n--- 3. Checking Public Comments (/artifacts/${artifactId}/comments) ---`);
  const commUrl = `${baseUrl}/artifacts/${artifactId}/comments`;
  const commRes = await fetch(commUrl, { headers });
  console.log(`Comments subcollection status: ${commRes.status}`);
  if (commRes.status === 200) {
    const commData = await commRes.json();
    const docs = commData.documents || [];
    console.log(`Total public comments: ${docs.length}`);
    docs.forEach(doc => console.log(`  - Comment ID: ${doc.name.split('/').pop()} | Fields: ${JSON.stringify(doc.fields)}`));
  } else if (commRes.status === 404) {
    console.log(`No comments subcollection exists yet (0 comments).`);
  } else {
    console.error(`Comments body: ${await commRes.text()}`);
  }

  // 4. Existing comment intents under /users/{listenerUid}/private/intents/comments
  console.log(`\n--- 4. Checking Comment Intents (/users/${listenerUid}/private/intents/comments) ---`);
  const intentUrl = `${baseUrl}/users/${listenerUid}/private/intents/comments`;
  const intentRes = await fetch(intentUrl, { headers });
  console.log(`Intents subcollection status: ${intentRes.status}`);
  if (intentRes.status === 200) {
    const intentData = await intentRes.json();
    const docs = intentData.documents || [];
    console.log(`Total comment intents for listener: ${docs.length}`);
    docs.forEach(doc => console.log(`  - Intent ID: ${doc.name.split('/').pop()} | Fields: ${JSON.stringify(doc.fields)}`));
  } else if (intentRes.status === 404) {
    console.log(`No comment intents subcollection exists yet for listener (0 intents).`);
  } else {
    console.error(`Intents body: ${await intentRes.text()}`);
  }

  // 5. Relevant Artifact statistics and notification documents
  console.log(`\n--- 5. Checking Notifications for Artifact / Recipient ---`);
  const notifQueryUrl = `${baseUrl}:runQuery`;
  const notifQueryBody = {
    structuredQuery: {
      from: [{ collectionId: 'notifications' }],
      where: {
        fieldFilter: {
          field: { fieldPath: 'artifactId' },
          op: 'EQUAL',
          value: { stringValue: artifactId }
        }
      }
    }
  };

  const notifRes = await fetch(notifQueryUrl, {
    method: 'POST',
    headers,
    body: JSON.stringify(notifQueryBody)
  });

  if (notifRes.status === 200) {
    const notifData = await notifRes.json();
    const notifDocs = (notifData || []).map(item => item.document).filter(Boolean);
    console.log(`Total notifications for artifact (${artifactId}): ${notifDocs.length}`);
    notifDocs.forEach(doc => console.log(`  - Notif ID: ${doc.name.split('/').pop()} | Fields: ${JSON.stringify(doc.fields)}`));
  } else {
    console.error(`Notifications query error: ${await notifRes.text()}`);
  }
}

main().catch(console.error);
