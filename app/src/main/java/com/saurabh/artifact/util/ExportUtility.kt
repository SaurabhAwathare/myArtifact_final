package com.saurabh.artifact.util

import android.content.Context
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportUtility @Inject constructor(
    private val draftDao: DraftDao,
    private val encryptedStorageManager: EncryptedStorageManager
) {

    /**
     * Bundles all drafts and metadata into a ZIP file for manual export.
     */
    suspend fun exportAllToZip(context: Context, outputFile: File) = withContext(Dispatchers.IO) {
        val drafts = draftDao.getAllDrafts()
        
        ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
            // 1. Export Drafts
            drafts.forEach { draft ->
                val audioFile = File(draft.localAudioPath)
                if (audioFile.exists()) {
                    val decryptedBytes = encryptedStorageManager.getEncryptedInputStream(audioFile).use { it.readBytes() }
                    
                    val entry = ZipEntry("drafts/${draft.id}/audio.mp3")
                    zipOut.putNextEntry(entry)
                    zipOut.write(decryptedBytes)
                    zipOut.closeEntry()
                    
                    // Metadata as JSON
                    val metadata = """
                        {
                          "id": "${draft.id}",
                          "title": "${draft.title}",
                          "emotion": "${draft.emotion}",
                          "tags": ${draft.tags},
                          "createdAt": ${draft.createdAt}
                        }
                    """.trimIndent()
                    
                    val metaEntry = ZipEntry("drafts/${draft.id}/metadata.json")
                    zipOut.putNextEntry(metaEntry)
                    zipOut.write(metadata.toByteArray())
                    zipOut.closeEntry()
                }
            }
            
            // 2. Export Database (Optional, for advanced recovery)
            val dbFile = context.getDatabasePath("artifact_db")
            if (dbFile.exists()) {
                val dbEntry = ZipEntry("internal/artifact.db")
                zipOut.putNextEntry(dbEntry)
                zipOut.write(dbFile.readBytes())
                zipOut.closeEntry()
            }
            
            // 3. Readme
            val readme = """
                Artifact Journal Export
                Generated on: ${System.currentTimeMillis()}
                
                This package contains your audio drafts and associated metadata.
                Drafts are decrypted for your convenience. Keep this package secure!
            """.trimIndent()
            
            val readmeEntry = ZipEntry("README.txt")
            zipOut.putNextEntry(readmeEntry)
            zipOut.write(readme.toByteArray())
            zipOut.closeEntry()
        }
    }
}
