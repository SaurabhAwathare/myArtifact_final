package com.saurabh.artifact.service

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.FeedDisplayItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for transforming the raw Artifact stream into a list of display items,
 * including the injection of "breath breaks" for pacing.
 */
@Singleton
class FeedSeparatorMapper @Inject constructor() {

    /**
     * Maps a PagingData of ArtifactItems to FeedDisplayItems and inserts breath breaks.
     * Logic: Insert a break after every 5th artifact (absoluteIndex 4, 9, 14, etc.).
     */
    fun mapToDisplayItems(pagingData: PagingData<FeedDisplayItem.ArtifactItem>): PagingData<FeedDisplayItem> {
        return pagingData.insertSeparators { before, after ->
            // Only consider inserting a break if we have a valid 'before' item.
            if (before != null && (before.absoluteIndex + 1) % 5 == 0 && after != null) {
                FeedDisplayItem.BreakItem(id = "break_after_${before.id}")
            } else {
                null
            }
        }
    }
}
