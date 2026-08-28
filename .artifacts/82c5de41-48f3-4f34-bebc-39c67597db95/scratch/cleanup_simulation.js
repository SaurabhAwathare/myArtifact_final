
const FIRESTORE_GET_LATENCY_MS = 100;
const FIRESTORE_COMMIT_LATENCY_MS = 400;
const STORAGE_DELETE_LATENCY_MS = 200;
const SEQUENTIAL_OVERHEAD_MS = 50;

function simulateDeleteQueryBatch(cardinality) {
    let time = 0;
    let totalDeleted = 0;
    while (true) {
        time += FIRESTORE_GET_LATENCY_MS;
        const size = Math.min(500, cardinality - totalDeleted);
        if (size <= 0) break;

        time += FIRESTORE_COMMIT_LATENCY_MS;
        totalDeleted += size;
        if (size < 500) break;
    }
    return time;
}

function simulateRecursiveDelete(cardinality) {
    // recursiveDelete is basically batches of 500 as well
    return simulateDeleteQueryBatch(cardinality);
}

function simulateCleanup(cardinalityMap) {
    let totalTime = 0;

    // 1 & 2. Audio & Safety Net
    totalTime += STORAGE_DELETE_LATENCY_MS * 2;

    // 3. Transcript
    totalTime += STORAGE_DELETE_LATENCY_MS * 2;

    // 4. Comments (Recursive)
    totalTime += simulateRecursiveDelete(cardinalityMap.comments || 0);

    // 5. Reactions (Recursive)
    totalTime += simulateRecursiveDelete(cardinalityMap.reactions || 0);

    // 6. Top-level Reactions
    totalTime += simulateDeleteQueryBatch(cardinalityMap.globalReactions || 0);

    // 7. Reaction Counts
    totalTime += FIRESTORE_COMMIT_LATENCY_MS;

    // 8. Notifications
    totalTime += simulateDeleteQueryBatch(cardinalityMap.notifications || 0);

    // 9. Engagement Records
    totalTime += simulateDeleteQueryBatch(cardinalityMap.engagement || 0);

    // 10. Ownership Record
    totalTime += FIRESTORE_COMMIT_LATENCY_MS;

    // 11. Private Feedback
    totalTime += simulateDeleteQueryBatch(cardinalityMap.feedback || 0);

    // 12. Artifact Plays
    totalTime += simulateDeleteQueryBatch(cardinalityMap.plays || 0);

    // 13. Final Artifact Doc
    totalTime += FIRESTORE_COMMIT_LATENCY_MS;

    // Add overhead per step
    totalTime += SEQUENTIAL_OVERHEAD_MS * 13;

    return totalTime;
}

const scenarios = [
    { label: "Low (100 each)", counts: { comments: 100, reactions: 100, globalReactions: 100, notifications: 100, engagement: 100, feedback: 100, plays: 100 } },
    { label: "Medium (1,000 each)", counts: { comments: 1000, reactions: 1000, globalReactions: 1000, notifications: 1000, engagement: 1000, feedback: 1000, plays: 1000 } },
    { label: "High (5,000 each)", counts: { comments: 5000, reactions: 5000, globalReactions: 5000, notifications: 5000, engagement: 5000, feedback: 5000, plays: 5000 } },
    { label: "Viral (10,000 each)", counts: { comments: 10000, reactions: 10000, globalReactions: 10000, notifications: 10000, engagement: 10000, feedback: 10000, plays: 10000 } }
];

console.log("Cleanup Simulation Results:");
scenarios.forEach(s => {
    const timeMs = simulateCleanup(s.counts);
    const timeS = (timeMs / 1000).toFixed(2);
    const status = timeMs > 60000 ? "!!! TIMEOUT !!!" : "OK";
    console.log(`${s.label.padEnd(20)}: ${timeS}s [${status}]`);
});
