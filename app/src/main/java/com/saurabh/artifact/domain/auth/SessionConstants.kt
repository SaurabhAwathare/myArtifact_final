package com.saurabh.artifact.domain.auth

object SessionConstants {
    /**
     * Tag applied to all WorkManager jobs that are specific to a single user session.
     * These jobs are cancelled immediately upon logout to prevent unauthenticated activity.
     */
    const val TAG_USER_SESSION_WORK = "user_session_work"
}
