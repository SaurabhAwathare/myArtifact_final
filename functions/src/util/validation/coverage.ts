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
 * Converts a Firestore coverage representation into a Base64 string for comparison.
 * Handles Node.js Buffer (Firestore Admin SDK), Uint8Array, Firestore Bytes objects, and null/undefined.
 * Throws TypeError for unsupported non-null/non-undefined data types.
 */
export function coverageToBase64(coverage: unknown): string {
  if (coverage === null || coverage === undefined) {
    return "";
  }

  if (Buffer.isBuffer(coverage)) {
    return coverage.toString("base64");
  }

  if (typeof coverage === "object" && coverage !== null) {
    const obj = coverage as Record<string, any>;
    if (typeof obj.toBase64 === "function") {
      return obj.toBase64();
    }
    if (typeof obj.toBuffer === "function") {
      const buf = obj.toBuffer();
      if (Buffer.isBuffer(buf)) {
        return buf.toString("base64");
      }
    }
    if (coverage instanceof Uint8Array || ArrayBuffer.isView(coverage)) {
      const view = coverage as ArrayBufferView;
      return Buffer.from(view.buffer, view.byteOffset, view.byteLength).toString("base64");
    }
  }

  throw new TypeError(`Unsupported coverage type: ${typeof coverage}`);
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
