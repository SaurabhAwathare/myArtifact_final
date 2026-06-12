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
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.util.MemoryManager
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.util.RescueTracker
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject

@Suppress("GrazieInspectionRunner")
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
    lateinit var memoryManager: Lazy<MemoryManager>

    @Inject
    lateinit var startupCoordinator: Lazy<StartupCoordinator>

    private var _imageLoader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
        
        setupRescueTracker()
        
        // Use a dedicated scope for non-UI initialization to avoid blocking Main
        val initScope = CoroutineScope(
            Dispatchers.Default + 
            SupervisorJob() + 
            CoroutineExceptionHandlerUtils.create("ArtifactApp", "InitScope failure")
        )
        
        // Defer coordinator slightly to allow App onCreate to complete and UI to bind
        initScope.launch(Dispatchers.Main) {
            delay(50.milliseconds) // Reduced delay
            startupCoordinator.get().start()
            StartupTracer.mark("StartupCoordinator Launched (Deferred)")
        }
    }

    private fun setupRescueTracker() {
        val rescueTracker = RescueTracker.getInstance(this)
        
        // Intercept crashes to note them in the tracker
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            rescueTracker.noteCrash()
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // If the app stays alive for 15 seconds, consider it a successful startup
        CoroutineScope(
            Dispatchers.Default + 
            CoroutineExceptionHandlerUtils.create("ArtifactApp", "RescueTracker scope failure") {
                rescueTracker.noteCrash()
            }
        ).launch {
            delay(15.seconds)
            rescueTracker.onStartupSuccess()
        }
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
                    .strongReferencesEnabled(enable = true)
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
        memoryManager.get().notifyTrim(level)
        
        // Release image caches if memory pressure is high
        if ((level >= TRIM_MEMORY_UI_HIDDEN) || (level >= 10 /* TRIM_MEMORY_RUNNING_LOW */)) {
            _imageLoader?.memoryCache?.clear()
        }
    }

    companion object {
        // Native libraries moved to background init in onCreate
    }
}
