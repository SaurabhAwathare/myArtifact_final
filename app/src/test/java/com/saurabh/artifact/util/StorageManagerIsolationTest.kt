package com.saurabh.artifact.util

import android.content.Context
import io.mockk.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StorageManagerIsolationTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var storageManager: StorageManager
    private lateinit var tempDir: File
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        tempDir = File("build/test_storage_root").apply { mkdirs() }
        cacheDir = File(tempDir, "cache").apply { mkdirs() }
        
        every { context.filesDir } returns File(tempDir, "files").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        every { context.getExternalFilesDir(any()) } returns null
        
        storageManager = StorageManager(context)
    }

    @Test
    fun `clearUserStorage purges legacy decrypted files from root cache`() {
        // Setup legacy files in root cache
        val legacyFile1 = File(cacheDir, "decrypted_draft1.m4a").apply { createNewFile() }
        val legacyFile2 = File(cacheDir, "decrypted_draft2.m4a").apply { createNewFile() }
        val unrelatedFile = File(cacheDir, "other_cache.txt").apply { createNewFile() }

        assertTrue(legacyFile1.exists())
        assertTrue(legacyFile2.exists())
        assertTrue(unrelatedFile.exists())

        // Execute cleanup
        storageManager.clearUserStorage()

        // Verify legacy files are gone
        assertFalse("Legacy file 1 should be deleted", legacyFile1.exists())
        assertFalse("Legacy file 2 should be deleted", legacyFile2.exists())
        assertTrue("Unrelated file should be preserved (as per whitelist logic)", unrelatedFile.exists())
    }

    @Test
    fun `clearUserStorage purges tempUploadDirectory`() {
        // Setup temp upload directory and files
        val uploadTempDir = storageManager.tempUploadDirectory
        val tempUploadFile = File(uploadTempDir, "decrypted_active.m4a").apply { createNewFile() }

        assertTrue(tempUploadFile.exists())

        // Execute cleanup
        storageManager.clearUserStorage()

        // Verify temp upload directory and its contents are gone
        assertFalse("Temp upload file should be deleted", tempUploadFile.exists())
        assertFalse("Temp upload directory should be deleted", uploadTempDir.exists())
    }
}
