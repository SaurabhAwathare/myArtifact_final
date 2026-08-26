/**
 * Authoritative moderation configuration for the Artifact platform.
 */
export const ModerationConfig = {
  /**
   * The number of unique reports required before an artifact is suppressed from recommendation feeds.
   */
  REPORT_SUPPRESSION_THRESHOLD: 3,

  /**
   * The number of safety concerns required before an artifact is suppressed.
   */
  SAFETY_CONCERN_SUPPRESSION_THRESHOLD: 3,

  /**
   * Status constants for recommendation state.
   */
  RecommendationState: {
    ACTIVE: "ACTIVE",
    SUPPRESSED: "SUPPRESSED",
  },

  /**
   * Status constants for moderation queue.
   */
  ModerationStatus: {
    PENDING_REVIEW: "PENDING_REVIEW",
    REVIEWED: "REVIEWED",
    ACTION_TAKEN: "ACTION_TAKEN",
  },
};
