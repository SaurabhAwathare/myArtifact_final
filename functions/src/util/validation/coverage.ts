import {getPolicy} from "./policy";
import {countSetBits} from "./bitset";

/**
 * Result of the coverage calculation.
 */
export interface CoverageResult {
  cardinality: number;
  totalSegments: number;
  coveragePercent: number;
  isValid: boolean;
}

/**
 * Calculates listening coverage using authoritative duration and policy.
 */
export function validateCoverage(
  durationMs: number,
  coverageBuffer: Buffer,
  hasReachedEnd: boolean,
  version?: number
): CoverageResult {
  const policy = getPolicy(version);
  const segmentSize = policy.getSegmentSizeMs(durationMs);
  const totalSegments = durationMs > 0 ?
    Math.max(1, Math.floor(durationMs / segmentSize)) : 1;

  const cardinality = countSetBits(coverageBuffer);
  const coveragePercent = cardinality / totalSegments;

  const isValid = coveragePercent >= policy.minCoverage &&
    (!policy.requireReachedEnd || hasReachedEnd);

  return {
    cardinality,
    totalSegments,
    coveragePercent,
    isValid,
  };
}
