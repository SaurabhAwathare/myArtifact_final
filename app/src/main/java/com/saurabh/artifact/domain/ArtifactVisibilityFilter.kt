package com.saurabh.artifact.domain

import com.saurabh.artifact.data.local.ReportedArtifactDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A centralized policy engine for determining artifact visibility.
 * This service unifies local suppression rules (like reporting) across all discovery surfaces.
 */
@Singleton
class ArtifactVisibilityFilter @Inject constructor(
    private val reportedArtifactDao: ReportedArtifactDao
) {
    /**
     * Fetches a one-shot snapshot of suppressed artifact IDs.
     * Useful for paging operations where a stable set is preferred for the lifetime of the source.
     */
    suspend fun getSuppressedIdsSnapshot(userId: String): Set<String> {
        return reportedArtifactDao.getReportedArtifactIds(userId).toSet()
    }

    /**
     * Provides a reactive stream of suppressed artifact IDs.
     * Useful for UI components (like Profile) that should update immediately when a new report is recorded.
     */
    fun observeSuppressedIds(userId: String): Flow<Set<String>> {
        return reportedArtifactDao.observeReportedArtifactIds(userId).map { it.toSet() }
    }
}
