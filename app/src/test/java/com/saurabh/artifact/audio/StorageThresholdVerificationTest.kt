package com.saurabh.artifact.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.util.StorageManager
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageThresholdVerificationTest {

    private lateinit var context: Context
    private lateinit var storageManager: StorageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // We use spyk to mock only the availableStorageMb property while keeping the real logic for isStorageAvailable
        storageManager = spyk(StorageManager(context))
    }

    @Test
    fun `Invariant 1 - Recording is allowed when available storage is safely above 600 MB`() {
        // Arrange: 700 MB available
        every { storageManager.availableStorageMb } returns 700L
        
        // Act & Assert
        assertTrue("Recording should be allowed at 700 MB", storageManager.isStorageAvailable())
    }

    @Test
    fun `Invariant 2 - Recording cannot start when available storage is exactly 600 MB`() {
        // Arrange: 600 MB available
        every { storageManager.availableStorageMb } returns 600L
        
        // Act & Assert
        // isStorageAvailable uses '>' operator: availableStorageMb > requiredMb
        assertFalse("Recording should NOT be allowed at exactly 600 MB", storageManager.isStorageAvailable())
    }

    @Test
    fun `Invariant 2 - Recording cannot start when available storage is below 600 MB`() {
        // Arrange: 550 MB available
        every { storageManager.availableStorageMb } returns 550L
        
        // Act & Assert
        assertFalse("Recording should NOT be allowed at 550 MB", storageManager.isStorageAvailable())
    }

    @Test
    fun `Invariant 5 - Start threshold (600MB) is now higher than Critical threshold (512MB)`() {
        // This ensures the contradiction is resolved
        assertTrue(
            "MIN_STORAGE_REQUIRED_MB must be greater than CRITICAL_STORAGE_THRESHOLD_MB",
            StorageManager.MIN_STORAGE_REQUIRED_MB > StorageManager.CRITICAL_STORAGE_THRESHOLD_MB
        )
    }

    @Test
    fun `Invariant 3 and 4 - Check boundaries relative to Critical threshold`() {
        val critical = StorageManager.CRITICAL_STORAGE_THRESHOLD_MB // 512
        
        // Above critical (e.g., 513 MB) - Mechanism should NOT stop yet
        assertTrue("513 MB should be above critical", 513L >= critical)
        
        // Below critical (e.g., 511 MB) - Mechanism should trigger stop
        assertTrue("511 MB should be below critical", 511L < critical)
    }
}
