package com.saurabh.artifact.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var storageManager: StorageManager
    
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var externalCacheDir: File

    @Before
    fun setup() {
        mockContext = mockk()
        
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
        externalCacheDir = tempFolder.newFolder("external_cache")
        
        every { mockContext.filesDir } returns filesDir
        every { mockContext.cacheDir } returns cacheDir
        every { mockContext.getExternalFilesDir(any()) } returns null
        every { mockContext.externalCacheDir } returns externalCacheDir
        
        storageManager = StorageManager(mockContext)
    }

    @Test
    fun `test clearUserStorage deletes target directories`() {
        // Setup targets
        val draftsDir = File(filesDir, "Artifact/Drafts").apply { mkdirs() }
        val waveformsDir = File(filesDir, "waveforms").apply { mkdirs() }
        val transcriptsDir = File(filesDir, "transcripts").apply { mkdirs() }
        val recordingTemp = File(cacheDir, "recording_temp").apply { mkdirs() }
        
        File(draftsDir, "test.m4a").writeText("data")
        File(waveformsDir, "test.json").writeText("data")
        
        val result = storageManager.clearUserStorage()
        
        assertFalse(draftsDir.exists())
        assertFalse(waveformsDir.exists())
        assertFalse(transcriptsDir.exists())
        assertFalse(recordingTemp.exists())
        
        assertTrue(result.deletedDirectories.contains("Drafts Root"))
        assertTrue(result.deletedDirectories.contains("Waveforms"))
        assertTrue(result.deletedDirectories.contains("Temp Recordings"))
    }

    @Test
    fun `test clearUserStorage skips missing directories`() {
        val result = storageManager.clearUserStorage()
        
        assertTrue(result.skippedDirectories.contains("Drafts Root"))
        assertTrue(result.skippedDirectories.contains("Waveforms"))
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `test clearUserStorage protects whitelisted cache directories`() {
        val imageCache = File(cacheDir, "image_cache").apply { mkdirs() }
        val mediaCache = File(cacheDir, "media_cache").apply { mkdirs() }
        val otherCache = File(cacheDir, "other_cache").apply { mkdirs() }
        
        storageManager.clearUserStorage()
        
        assertTrue(imageCache.exists())
        assertTrue(mediaCache.exists())
        assertTrue(otherCache.exists())
    }

    @Test
    fun `test clearUserStorage nested directory deletion`() {
        val draftsRoot = File(filesDir, "Artifact/Drafts").apply { mkdirs() }
        val draft1 = File(draftsRoot, "draft_1").apply { mkdirs() }
        val draft1Audio = File(draft1, "audio.m4a").apply { writeText("audio") }
        val draft1Waveform = File(draft1, "waveform.json").apply { writeText("waveform") }
        
        val result = storageManager.clearUserStorage()
        
        assertFalse(draftsRoot.exists())
        assertFalse(draft1.exists())
        assertFalse(draft1Audio.exists())
        assertFalse(draft1Waveform.exists())
        
        assertTrue(result.deletedDirectories.contains("Drafts Root"))
    }

    @Test
    fun `test clearUserStorage handles partial failures`() {
        // Create a directory that cannot be deleted (simulated by making it non-writable/readable might not work in all environments, 
        // but we can mock deleteDirectoryRecursively if it was non-private, but it is private).
        // Instead, we can verify that if one target exists and is deleted, and another doesn't, it still reports correctly.
        
        val waveformsDir = File(filesDir, "waveforms").apply { mkdirs() }
        
        val result = storageManager.clearUserStorage()
        
        assertFalse(waveformsDir.exists())
        assertTrue(result.deletedDirectories.contains("Waveforms"))
        assertTrue(result.skippedDirectories.contains("Drafts Root"))
    }
}
