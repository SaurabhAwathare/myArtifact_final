package com.saurabh.artifact.ui.feed

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.abs

/**
 * ViewportCoordinator - Manages the progressive hydration of the feed based on scroll state.
 * Implements "Settled Viewport Hydration" to minimize rendering pressure during scroll.
 */
@OptIn(FlowPreview::class)
@Composable
fun ViewportCoordinator(
    state: LazyListState,
    viewModel: FeedViewModel
) {
    val currentViewModel by rememberUpdatedState(viewModel)

    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo }
            .debounce(300.milliseconds) // Wait for scrolling to slow down or stop
            .distinctUntilChanged()
            .filter { !state.isScrollInProgress } // Only hydrate when stationary
            .collect { visibleItems ->
                val updates = mutableMapOf<String, HydrationLevel>()
                val viewportCenter = (state.layoutInfo.viewportEndOffset + state.layoutInfo.viewportStartOffset) / 2
                
                visibleItems.forEach { item ->
                    val key = item.key as? String ?: return@forEach
                    if (key.startsWith("header") || key.startsWith("break")) return@forEach

                    // Normalize ID by removing UI-specific prefixes (e.g., "unf_" for unfinished items)
                    // This prevents UI-specific keys from leaking into the data layer.
                    val artifactId = key.removePrefix("unf_")

                    // Calculate distance from center to determine priority
                    val itemCenter = item.offset + (item.size / 2)
                    val distanceFromCenter = abs(itemCenter - viewportCenter)
                    
                    val level = when {
                        // The most centered item gets FULL hydration
                        distanceFromCenter < item.size / 2 -> {
                            currentViewModel.onArtifactFocused(artifactId) // Trigger heavy data load with canonical ID
                            HydrationLevel.FULL
                        }
                        // Immediately visible items get ENRICHED
                        distanceFromCenter < state.layoutInfo.viewportEndOffset -> HydrationLevel.ENRICHED
                        // Others get METADATA
                        else -> HydrationLevel.METADATA
                    }
                    
                    // Use canonical ID instead of UI-specific key for hydration state.
                    // This ensures state is shared across all visual instances of the same artifact.
                    val currentLevel = updates[artifactId] ?: HydrationLevel.SHELL
                    if (level > currentLevel) {
                        updates[artifactId] = level

                        // Phase 1: Enable Audio Pre-cache for stationary, visible items
                        if (level >= HydrationLevel.ENRICHED) {
                            currentViewModel.preCacheArtifact(artifactId)
                        }
                    }
                    currentViewModel.hydrateArtifact(artifactId) // Mark as basic hydrated with canonical ID
                }
                
                if (updates.isNotEmpty()) {
                    currentViewModel.updateHydrationLevels(updates)
                }
            }
    }
}
