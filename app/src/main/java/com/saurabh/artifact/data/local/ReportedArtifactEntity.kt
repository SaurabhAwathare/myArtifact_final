package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "reported_artifacts",
    primaryKeys = ["userId", "artifactId"],
    indices = [Index(value = ["artifactId"])]
)
data class ReportedArtifactEntity(
    val userId: String,
    val artifactId: String,
    val reportedAt: Long = System.currentTimeMillis()
)
