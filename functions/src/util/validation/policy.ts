/**
 * Defines the parameters for Review Policy V1.
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
