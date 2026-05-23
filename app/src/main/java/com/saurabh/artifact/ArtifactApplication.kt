package com.saurabh.artifact

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.google.firebase.FirebaseApp
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.util.MemoryManager
import com.saurabh.artifact.util.StartupTracer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ArtifactApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var startupCoordinator: StartupCoordinator

    private var _imageLoader: ImageLoader? = null

    override fun onCreate() {
        StartupTracer.mark("App onCreate Started")
        StartupMetrics.onAppCreate()
        super.onCreate()
        
        // Use a dedicated scope for non-UI initialization to avoid blocking Main
        val initScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        initScope.launch {
            // Initialize Firebase in background - Firebase handles its own internal locking
            FirebaseApp.initializeApp(this@ArtifactApplication)
            StartupTracer.mark("Firebase Initialized (Background)")
        }

        startupCoordinator.start()
        StartupTracer.mark("StartupCoordinator Launched")
    }

    /**
     * Optimized ImageLoader configuration for memory-constrained environments.
     * Uses 15% of available heap for memory cache and enables bitmap pooling.
     */
    override fun newImageLoader(): ImageLoader {
        val loader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // Limit to 15% of app memory
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // 2% of disk space
                    .build()
            }
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
        _imageLoader = loader
        return loader
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("ArtifactApp", "onTrimMemory level: $level")
        
        // Notify centralized memory manager
        if (::memoryManager.isInitialized) {
            memoryManager.notifyTrim(level)
        }
        
        // Release image caches if memory pressure is high
        if (level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW) {
            _imageLoader?.memoryCache?.clear()
        }
    }
}
