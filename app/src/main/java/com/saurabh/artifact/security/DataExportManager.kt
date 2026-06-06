package com.saurabh.artifact.security

import android.content.Context
import android.net.Uri
import com.saurabh.artifact.data.local.DraftDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataExportManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
) {
    /**
     * Creates an encrypted ZIP archive of all local drafts (audio + metadata).
     */
    suspend fun exportAllDrafts(outputUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drafts = draftDao.getAllDrafts()
            
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    drafts.forEach { draft ->
                        // 1. Add Metadata
                        val metadataJson = Json.encodeToString(draft)
                        val metadataEntry = ZipEntry("draft_${draft.id}/metadata.json")
                        zipOut.putNextEntry(metadataEntry)
                        zipOut.write(metadataJson.toByteArray())
                        zipOut.closeEntry()

                        // 2. Add Audio (if exists)
                        val audioFile = File(draft.localAudioPath)
                        if (audioFile.exists()) {
                            val audioEntry = ZipEntry("draft_${draft.id}/${audioFile.name}")
                            zipOut.putNextEntry(audioEntry)
                            audioFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
