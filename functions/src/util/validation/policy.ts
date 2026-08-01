/**
 * Defines the parameters for Review Policy V1 (Legacy Bucketed).
 */
export const POLICY_V1 = {
  version: 1,
  minCoverage: 0.95,
  requireReachedEnd: true,
  getSegmentSizeMs: (durationMs: number): number => {
    if (durationMs < 60000) return 500; // < 1 min
    if (durationMs < 600000) return 5000; // < 10 mins
    return 10000; // > 10 mins
  },
};

/**
 * Defines the parameters for Review Policy V2 (Fixed 1s Resolution).
 */
export const POLICY_V2 = {
  version: 2,
  minCoverage: 0.95,
  requireReachedEnd: true,
  getSegmentSizeMs: (_durationMs: number): number => 1000,
};

/**
 * Returns the appropriate policy for a given version.
 * Defaults to V1 for backward compatibility.
 */
export function getPolicy(version?: number) {
  switch (version) {
  case 2: return POLICY_V2;
  case 1:
  default: return POLICY_V1;
  }
}
