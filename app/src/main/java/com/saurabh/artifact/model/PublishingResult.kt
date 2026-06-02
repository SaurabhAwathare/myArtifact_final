package com.saurabh.artifact.model

/**
 * Represents the immediate outcome of initiating the publishing process.
 */
enum class PublishingResult {
    /**
     * The device is online, and the upload process has been enqueued to start immediately.
     */
    UPLOAD_STARTED,

    /**
     * The device is offline. The artifact has been saved and queued to publish 
     * automatically when the network returns.
     */
    QUEUED_OFFLINE,

    /**
     * The artifact is already in the process of being published or is already published.
     */
    ALREADY_IN_PROGRESS,

    /**
     * A failure occurred before publishing could be initiated.
     */
    FAILED
}
