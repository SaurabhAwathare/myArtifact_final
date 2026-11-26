/**
 * Authoritative Validation for Artifact Play Events and Counter Integrity.
 * Milestone 2 — Phase 2.15C.2.1
 */

export interface ArtifactPlayEventData {
  playId: string;
  userId: string;
  artifactId: string;
  sessionIdentity: string;
  playbackDurationMs: number;
  clientOccurredAt: any;
  serverReceivedAt: any;
}

export interface PlayValidationSuccess {
  valid: true;
  data: ArtifactPlayEventData;
}

export interface PlayValidationFailure {
  valid: false;
  reason: string;
  field?: string;
  details?: Record<string, unknown>;
}

export type PlayValidationResult = PlayValidationSuccess | PlayValidationFailure;

/**
 * Stage C — Counter Integrity
 *
 * A qualified play counter is valid if:
 * 1. It is undefined (legacy artifacts without qualifiedPlayCount default to 0 on increment).
 * 2. It is a non-negative safe integer.
 */
export function isValidQualifiedCounter(value: unknown): boolean {
  if (value === undefined) return true;

  return (
    typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0
  );
}

/**
 * Checks if a counter is already at the maximum safe integer boundary
 * and cannot be incremented safely.
 */
export function isCounterExhausted(value: number | undefined): boolean {
  return value === Number.MAX_SAFE_INTEGER;
}

/**
 * Allowed exact set of fields in artifact_plays documents.
 */
export const ALLOWED_PLAY_FIELDS = [
  "playId",
  "userId",
  "artifactId",
  "sessionIdentity",
  "playbackDurationMs",
  "clientOccurredAt",
  "serverReceivedAt",
] as const;

/**
 * Standard identifier regex for UID, Artifact ID, and Session Identity.
 * Requires 1 to 128 characters: alphanumeric, underscore, hyphen, or dot.
 */
export const PLAY_IDENTIFIER_REGEX = /^[A-Za-z0-9_.-]{1,128}$/;

/**
 * Minimum listening time required for qualification: 10,000 milliseconds (10 seconds).
 */
export const MIN_QUALIFIED_PLAYBACK_MS = 10000;

/**
 * Offline synchronization allowance: 30 days in milliseconds.
 */
export const MAX_OFFLINE_SYNC_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * Maximum allowable future clock skew: 5 minutes in milliseconds.
 */
export const MAX_FUTURE_CLOCK_SKEW_MS = 5 * 60 * 1000;

/**
 * Extracts milliseconds from a Firestore Timestamp or equivalent object.
 * Returns null if the value is not a valid Timestamp.
 */
export function extractTimestampMs(value: unknown): number | null {
  if (!value || typeof value !== "object") {
    return null;
  }

  // Check for Firestore Timestamp instance or duck-typed method
  const candidate = value as any;
  if (typeof candidate.toMillis === "function") {
    const ms = candidate.toMillis();
    return typeof ms === "number" && Number.isSafeInteger(ms) && ms > 0 ? ms : null;
  }

  if (
    typeof candidate.seconds === "number" &&
    Number.isSafeInteger(candidate.seconds) &&
    typeof candidate.nanoseconds === "number" &&
    Number.isSafeInteger(candidate.nanoseconds) &&
    candidate.seconds >= 0 &&
    candidate.nanoseconds >= 0
  ) {
    const ms = candidate.seconds * 1000 + Math.floor(candidate.nanoseconds / 1e6);
    return Number.isSafeInteger(ms) && ms > 0 ? ms : null;
  }

  return null;
}

/**
 * Stage D — Dedicated Independent Backend Event Validation
 *
 * Validates artifact_plays document payload against all backend rules.
 */
export function validateArtifactPlayEvent(
  playDocId: string,
  data: unknown,
  options?: { nowMs?: number }
): PlayValidationResult {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    return {
      valid: false,
      reason: "MALFORMED_PAYLOAD",
      details: { message: "Payload must be a non-null, non-array object" },
    };
  }

  const record = data as Record<string, unknown>;
  const keys = Object.keys(record);

  // 1. Exact field set validation
  if (keys.length !== ALLOWED_PLAY_FIELDS.length) {
    return {
      valid: false,
      reason: "EXACT_FIELD_SET_VIOLATION",
      details: {
        expectedFields: ALLOWED_PLAY_FIELDS,
        actualFields: keys,
      },
    };
  }

  for (const field of ALLOWED_PLAY_FIELDS) {
    if (!(field in record)) {
      return {
        valid: false,
        reason: "MISSING_REQUIRED_FIELD",
        field,
      };
    }
  }

  // 2. Document ID and playId consistency
  const playId = record.playId;
  if (typeof playId !== "string" || playId !== playDocId) {
    return {
      valid: false,
      reason: "DOCUMENT_ID_MISMATCH",
      field: "playId",
      details: { playDocId, playId },
    };
  }

  // 3. Identifiers validation
  const userId = record.userId;
  if (typeof userId !== "string" || !PLAY_IDENTIFIER_REGEX.test(userId)) {
    return {
      valid: false,
      reason: "INVALID_IDENTIFIER",
      field: "userId",
      details: { userId },
    };
  }

  const artifactId = record.artifactId;
  if (typeof artifactId !== "string" || !PLAY_IDENTIFIER_REGEX.test(artifactId)) {
    return {
      valid: false,
      reason: "INVALID_IDENTIFIER",
      field: "artifactId",
      details: { artifactId },
    };
  }

  const sessionIdentity = record.sessionIdentity;
  if (typeof sessionIdentity !== "string" || !PLAY_IDENTIFIER_REGEX.test(sessionIdentity)) {
    return {
      valid: false,
      reason: "INVALID_IDENTIFIER",
      field: "sessionIdentity",
      details: { sessionIdentity },
    };
  }

  // 4. Deterministic ID construction: "play_{userId}_{sessionIdentity}"
  const expectedDeterministicId = `play_${userId}_${sessionIdentity}`;
  if (playId !== expectedDeterministicId) {
    return {
      valid: false,
      reason: "NON_DETERMINISTIC_PLAY_ID",
      field: "playId",
      details: { playId, expectedDeterministicId },
    };
  }

  // 5. Playback duration validation (safe integer >= 10,000)
  const playbackDurationMs = record.playbackDurationMs;
  if (
    typeof playbackDurationMs !== "number" ||
    !Number.isSafeInteger(playbackDurationMs) ||
    playbackDurationMs < MIN_QUALIFIED_PLAYBACK_MS
  ) {
    return {
      valid: false,
      reason: "INVALID_PLAYBACK_DURATION",
      field: "playbackDurationMs",
      details: {
        playbackDurationMs,
        minRequired: MIN_QUALIFIED_PLAYBACK_MS,
      },
    };
  }

  // 6. Timestamps validation
  const clientOccurredAtMs = extractTimestampMs(record.clientOccurredAt);
  if (clientOccurredAtMs === null) {
    return {
      valid: false,
      reason: "INVALID_TIMESTAMP_TYPE",
      field: "clientOccurredAt",
    };
  }

  const serverReceivedAtMs = extractTimestampMs(record.serverReceivedAt);
  if (serverReceivedAtMs === null) {
    return {
      valid: false,
      reason: "INVALID_TIMESTAMP_TYPE",
      field: "serverReceivedAt",
    };
  }

  // 7. 30-day offline timestamp window & future clock skew
  // clientOccurredAt cannot be older than 30 days before serverReceivedAt
  if (serverReceivedAtMs - clientOccurredAtMs > MAX_OFFLINE_SYNC_WINDOW_MS) {
    return {
      valid: false,
      reason: "CLIENT_OCCURRED_TOO_OLD",
      field: "clientOccurredAt",
      details: {
        serverReceivedAtMs,
        clientOccurredAtMs,
        maxWindowMs: MAX_OFFLINE_SYNC_WINDOW_MS,
      },
    };
  }

  // clientOccurredAt cannot be in the future beyond allowed skew relative to serverReceivedAt
  if (clientOccurredAtMs > serverReceivedAtMs + MAX_FUTURE_CLOCK_SKEW_MS) {
    return {
      valid: false,
      reason: "CLIENT_OCCURRED_IN_FUTURE",
      field: "clientOccurredAt",
      details: {
        serverReceivedAtMs,
        clientOccurredAtMs,
        maxSkewMs: MAX_FUTURE_CLOCK_SKEW_MS,
      },
    };
  }

  // 8. Server timestamp sanity (relative to current server time if provided)
  const nowMs = options?.nowMs ?? Date.now();
  if (serverReceivedAtMs > nowMs + MAX_FUTURE_CLOCK_SKEW_MS) {
    return {
      valid: false,
      reason: "SERVER_RECEIVED_IN_FUTURE",
      field: "serverReceivedAt",
      details: { serverReceivedAtMs, nowMs },
    };
  }
  if (nowMs - serverReceivedAtMs > MAX_OFFLINE_SYNC_WINDOW_MS) {
    return {
      valid: false,
      reason: "SERVER_RECEIVED_STALE",
      field: "serverReceivedAt",
      details: { serverReceivedAtMs, nowMs },
    };
  }

  return {
    valid: true,
    data: {
      playId,
      userId,
      artifactId,
      sessionIdentity,
      playbackDurationMs,
      clientOccurredAt: record.clientOccurredAt,
      serverReceivedAt: record.serverReceivedAt,
    },
  };
}
