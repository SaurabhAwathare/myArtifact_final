# Research Notes: Review Segmentation Fairness Audit

## Segmentation Analysis

The `ReviewPolicy` defines the following segment sizing strategy:
- **< 60s:** 500ms (Total segments: 2 to 119)
- **60s - 600s:** 5000ms (5s) (Total segments: 12 to 119)
- **> 600s:** 10000ms (10s) (Total segments: 60+)

The `PublishingReviewValidator` calculates `totalSegments` as `(durationMs / segmentSize).toInt()`.
The required coverage is `minCoverage = 0.95`.

### 95% Mathematical Constraints
A listener can only "afford" to miss a segment if:
$$totalSegments \times (1.0 - 0.95) \ge 1$$
$$totalSegments \times 0.05 \ge 1$$
$$totalSegments \ge 20$$

For a 5s segment size, 20 segments correspond to:
$$20 \times 5000ms = 100,000ms = 100s$$

### The 60-Second Strictness Cliff
| Duration | Segments | Min Coverage Needed | Effective Req. | Missable Segments |
| :--- | :--- | :--- | :--- | :--- |
| 59.5s | 119 | 113.05 -> 114 | 95.8% | 5 |
| 60.0s | 12 | 11.4 -> 12 | **100%** | **0** |
| 75.0s | 15 | 14.25 -> 15 | **100%** | **0** |
| 90.0s | 18 | 17.1 -> 18 | **100%** | **0** |
| 95.0s | 19 | 18.05 -> 19 | **100%** | **0** |
| 100.0s | 20 | 19.0 -> 19 | 95% | 1 |

> [!WARNING]
> **Strictness Cliff Detected**: At exactly 60 seconds, the "Effective Required Coverage" jumps from ~95% to 100%. A user must hit every single 5-second window. If they miss even one tick in a 5s window (due to a small lag or speed spike), they fail validation.

## Trace Analysis

### 60s Duration
- `segmentSize = 5000ms`
- `totalSegments = 12`
- Required to satisfy 95%: 12 segments (11/12 = 91.6%)
- **Trap:** 100% coverage required.

### 75s Duration
- `segmentSize = 5000ms`
- `totalSegments = 15`
- Required: 15 segments.
- **Trap:** 100% coverage required.

### 120s Duration
- `segmentSize = 5000ms`
- `totalSegments = 24`
- Required: 23 segments.
- Effective req: 95.8%. 1 segment missable.

## Impact of Terminal Playback Fix
The fix in `ReviewAuthorityService` ensures that upon `STATE_ENDED`, one final tick is processed at the `finalPos` and `hasReachedEnd` is set.
This ensures the *last* segment is set if the player reached the end.
However, it does **not** resolve the "100% trap" for the segments *between* start and end. If a user is listening at 3x speed and there is a system-level stutter, they might skip a 5s window.

## Discontinuities and Integer Rounding
`totalSegments = (durationMs / segmentSize).toInt()`
If `durationMs = 64,999`, `totalSegments = 12`.
The 13th partial segment `[60000, 64999]` is completely ignored in the denominator, but also cannot be "set" in the numerator because `segmentIndex = currentPosMs / 5000` will be `12` for `currentPosMs >= 60000`, and the tracker checks `if (segmentIndex < totalSegments)`.
This means the last ~4.9 seconds of a 64.9s file are **invisible** to the coverage tracker but the user must still reach the end (`hasReachedEnd`) to pass. This part is actually "lenient" (you can skip the last 4.9s and still get 100% coverage as long as you reach the end state).

## Conclusion (Initial)
The terminal fix handles the "Reached End" bit reliably, but the segmentation math creates a "Fairness Gap" for artifacts between 60s and 100s.
