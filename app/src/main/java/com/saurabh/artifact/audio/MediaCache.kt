package com.saurabh.artifact.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

import android.os.SystemClock
import android.util.Log

/**
 * Singleton object to manage Media3 SimpleCache instance.
 */
@UnstableApi
object MediaCache {
    private var instance: SimpleCache? = null
    private const val CACHE_SIZE = 500 * 1024 * 1024L // 500MB

    @Synchronized
    fun getInstance(context: Context): SimpleCache {
        if (instance == null) {
            Log.d("MediaCache", "SIMPLE_CACHE_INIT_START")
            val startTime = SystemClock.elapsedRealtime()
            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, "media_cache")
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
            instance = SimpleCache(cacheDir, evictor, databaseProvider)
            val elapsed = SystemClock.elapsedRealtime() - startTime
            Log.d("MediaCache", "SIMPLE_CACHE_INIT_END: elapsed=${elapsed}ms")
        }
        return instance!!
    }

    /**
     * Releases the cache instance and allows the directory to be cleared.
     */
    @Synchronized
    fun release() {
        instance?.release()
        instance = null
    }
}
