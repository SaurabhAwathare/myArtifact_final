function calculateStats(durationMs, segmentSizeMs) {
    const totalSegments = Math.max(1, Math.floor(durationMs / segmentSizeMs));
    const requiredCovered = Math.ceil(0.95 * totalSegments);
    const effectiveCoverage = requiredCovered / totalSegments;
    const missableSegments = totalSegments - requiredCovered;
    const isFair = missableSegments > 0;
    return {
        duration: durationMs / 1000,
        segmentSize: segmentSizeMs,
        totalSegments: totalSegments,
        requiredCovered: requiredCovered,
        effectiveCoverage: effectiveCoverage,
        missableSegments: missableSegments,
        isFair: isFair ? "Fair" : "Unfair (100% Trap)"
    };
}

const durations = [30, 60, 75, 90, 100, 120, 300, 600, 1800];
const segmentSizes = [500, 1000, 1500, 2000, 2500, 3000, 4000, 5000];

console.log("| Segment Size (ms) | Duration (s) | Total Segments | Req. Covered | Effective Req. | Missable | Status |");
console.log("|---:|---:|---:|---:|---:|---:|---|");

for (const ss of segmentSizes) {
    for (const d of durations) {
        const stats = calculateStats(d * 1000, ss);
        console.log(`| ${stats.segmentSize} | ${stats.duration} | ${stats.totalSegments} | ${stats.requiredCovered} | ${(stats.effectiveCoverage * 100).toFixed(1)}% | ${stats.missableSegments} | ${stats.isFair} |`);
    }
}

console.log("\n--- PERFORMANCE ANALYSIS (2-hour Artifact) ---");
const duration2hMs = 2 * 3600 * 1000;
for (const ss of segmentSizes) {
    const totalSegments = Math.floor(duration2hMs / ss);
    const bitsetBytes = Math.ceil(totalSegments / 8);
    console.log(`Segment Size: ${ss}ms`);
    console.log(`  Total Segments: ${totalSegments}`);
    console.log(`  BitSet Memory: ${bitsetBytes} bytes (${(bitsetBytes / 1024).toFixed(2)} KB)`);
}
