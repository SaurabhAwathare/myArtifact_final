package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.saurabh.artifact.data.remote.model.CommentDto

/**
 * Maps a Firestore DTO to a domain Comment model.
 */
fun CommentDto.toDomain(): Comment {
    return Comment(
        id = id,
        artifactId = artifactId,
        creatorId = creatorId,
        author = author,
        text = text,
        createdAt = createdAt ?: Timestamp.now(),
        updatedAt = updatedAt ?: Timestamp.now(),
        status = try {
            CommentStatus.valueOf(status.uppercase())
        } catch (_: Exception) {
            CommentStatus.ACTIVE
        },
        identityVersion = identityVersion
    )
}

/**
 * Maps a domain Comment model to a Firestore DTO.
 */
fun Comment.toDto(): CommentDto {
    return CommentDto(
        id = id,
        artifactId = artifactId,
        creatorId = creatorId,
        author = author,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = status.name,
        identityVersion = identityVersion
    )
}
