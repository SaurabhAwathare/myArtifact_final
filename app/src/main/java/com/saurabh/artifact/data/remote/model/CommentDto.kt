package com.saurabh.artifact.data.remote.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.saurabh.artifact.model.AuthorSnapshot

/**
 * Firestore DTO for comments.
 * Ensures compatibility with Firestore serialization and deserialization.
 */
data class CommentDto(
    @get:Exclude var id: String = "",
    var artifactId: String = "",
    var creatorId: String = "",
    var author: AuthorSnapshot = AuthorSnapshot(),
    var text: String = "",
    @ServerTimestamp var createdAt: Timestamp? = null,
    @ServerTimestamp var updatedAt: Timestamp? = null,
    var status: String = "ACTIVE",
    var identityVersion: Long = 0
) {
    // Empty constructor for Firestore
    constructor() : this("")
}
