package com.saurabh.artifact.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

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
            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, "media_cache")
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
            instance = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return instance!!
    }
}
