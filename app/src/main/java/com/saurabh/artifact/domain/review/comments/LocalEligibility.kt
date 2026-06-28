package com.saurabh.artifact.domain.review.comments

/**
 * Represents the eligibility status of an artifact based on local evidence or server confirmation.
 */
enum class LocalEligibility {
    /** The user has not met the local immersion threshold. */
    NOT_ELIGIBLE,

    /** The user has met the threshold locally based on Room evidence. */
    ELIGIBLE_LOCAL,

    /** The server has confirmed the unlock state. */
    ELIGIBLE_SERVER_CONFIRMED
}
