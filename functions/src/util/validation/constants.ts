/**
 * Authoritative reasons for unlocking an artifact.
 */
export enum UnlockReason {
  LISTENING_THRESHOLD = "LISTENING_THRESHOLD",
  ADMIN_OVERRIDE = "ADMIN_OVERRIDE",
  CREATOR_EXCEPTION = "CREATOR_EXCEPTION",
}

/**
 * Current authoritative versions.
 */
export const POLICY_VERSION = 1;
export const VALIDATION_VERSION = 1;
