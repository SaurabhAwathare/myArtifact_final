import math

def calculate_stats(duration_ms, segment_size_ms):
    total_segments = max(1, duration_ms // segment_size_ms)
    required_covered = math.ceil(0.95 * total_segments)
    effective_coverage = required_covered / total_segments
    missable_segments = total_segments - required_covered
    is_fair = missable_segments > 0
    return {
        "duration": duration_ms / 1000,
        "segment_size": segment_size_ms,
        "total_segments": total_segments,
        "required_covered": required_covered,
        "effective_coverage": effective_coverage,
        "missable_segments": missable_segments,
        "is_fair": "Fair" if is_fair else "Unfair (100% Trap)"
    }

durations = [30, 60, 75, 90, 100, 120, 300, 600, 1800]
segment_sizes = [500, 1000, 1500, 2000, 2500, 3000, 4000, 5000]

print("| Segment Size (ms) | Duration (s) | Total Segments | Req. Covered | Effective Req. | Missable | Status |")
print("|---:|---:|---:|---:|---:|---:|---|")

for ss in segment_sizes:
    for d in durations:
        stats = calculate_stats(d * 1000, ss)
        print(f"| {stats['segment_size']} | {stats['duration']} | {stats['total_segments']} | {stats['required_covered']} | {stats['effective_coverage']:.1%} | {stats['missable_segments']} | {stats['is_fair']} |")

print("\n--- PERFORMANCE ANALYSIS (2-hour Artifact) ---")
duration_2h_ms = 2 * 3600 * 1000
for ss in segment_sizes:
    total_segments = duration_2h_ms // ss
    bitset_bytes = math.ceil(total_segments / 8)
    print(f"Segment Size: {ss}ms")
    print(f"  Total Segments: {total_segments}")
    print(f"  BitSet Memory: {bitset_bytes} bytes ({bitset_bytes/1024:.2f} KB)")
    # Tracking frequency assumes a 500ms tick from the player.
    # If SS < 500, we miss segments. If SS > 500, we hit same segment multiple times.
    # Actually, the tracker usually ticks at a frequency that matches the smallest possible segment size.
    # Current code uses 500ms for < 1 min, so the tracker probably ticks every 500ms or faster.
