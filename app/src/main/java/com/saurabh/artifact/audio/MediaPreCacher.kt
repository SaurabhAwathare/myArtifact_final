package com.saurabh.artifact.audio

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility to pre-cache media files in the background using Media3's CacheWriter.
 * This allows downloading content into [MediaCache] without requiring an active ExoPlayer.
 */
@UnstableApi
object MediaPreCacher {
    private val scope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.IO + 
        CoroutineExceptionHandlerUtils.create("MediaPreCacher", "Caching failure")
    )
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Starts a background job to cache the given URL.
     * If a job is already running for this URL, it does nothing.
     */
    fun preCache(context: Context, url: String) {
        if (url.isBlank()) return
        if (activeJobs.containsKey(url)) return

        val job = scope.launch {
            try {
                val cache = MediaCache.getInstance(context)
                val dataSpec = DataSpec(url.toUri())
                
                // Create a CacheDataSource that writes to our SimpleCache
                val dataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                    .createDataSource()
                
                Log.d("MediaPreCacher", "Starting pre-cache for: $url")
                
                val cacheWriter = CacheWriter(
                    dataSource,
                    dataSpec,
                    null,
                    null // No progress listener for now to keep it simple
                )
                
                cacheWriter.cache()
                Log.d("MediaPreCacher", "Successfully pre-cached: $url")
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.d("MediaPreCacher", "Pre-cache canceled for: $url")
                } else {
                    Log.e("MediaPreCacher", "Failed to pre-cache: $url", e)
                }
            } finally {
                activeJobs.remove(url)
            }
        }
        activeJobs[url] = job
    }

}
