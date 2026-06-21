package com.saurabh.artifact.repository

import com.saurabh.artifact.model.User

/**
 * Result of a profile retrieval or creation operation.
 * @property user The user profile object.
 * @property isNewUser True if the user profile was just created in this operation.
 */
data class ProfileResult(
    val user: User,
    val isNewUser: Boolean
)
