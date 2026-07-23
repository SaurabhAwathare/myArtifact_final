package com.saurabh.artifact.security

import android.content.Context
import android.net.Uri
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataExportManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val encryptionManager: BackupEncryptionManager,
    private val authRepository: AuthRepository
) {
    init {
        StreamingAeadConfig.register()
    }

    /**
     * Creates an encrypted archive (.artx) of all local drafts (audio + metadata).
     * Uses Tink's StreamingAEAD with a domain-separated export key.
     */
    suspend fun exportAllDrafts(outputUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drafts = draftDao.getAllDrafts()
            val exportKey = encryptionManager.getExportKey()
            val userId = authRepository.currentUserId.ifEmpty { "anonymous" }
            
            // Associated Data (AAD) for format versioning and user isolation
            val aad = "artifact_export_v1:$userId".toByteArray()
            
            // Modern Tink API: Create a KeysetHandle from the derived key
            val parameters = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB as AesGcmHkdfStreamingParameters

            val keysetHandle = KeysetHandle.newBuilder()
                .addEntry(
                    KeysetHandle.importKey(
                        AesGcmHkdfStreamingKey.create(
                            parameters,
                            SecretBytes.copyFrom(exportKey.encoded, InsecureSecretKeyAccess.get())
                        )
                    ).makePrimary().withFixedId(1)
                )
                .build()

            val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)

            context.contentResolver.openOutputStream(outputUri)?.use { rawOutputStream: OutputStream ->
                // Wrap the output stream with encryption
                streamingAead.newEncryptingStream(rawOutputStream, aad).use { encryptedStream ->
                    ZipOutputStream(encryptedStream).use { zipOut ->
                        drafts.forEach { draft ->
                            // 1. Add Metadata
                            val metadataJson = Json.encodeToString(draft)
                            val metadataEntry = ZipEntry("draft_${draft.id}/metadata.json")
                            zipOut.putNextEntry(metadataEntry)
                            zipOut.write(metadataJson.toByteArray())
                            zipOut.closeEntry()

                            // 2. Add Audio (if exists)
                            val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
                            val audioFile = File(audioPath)
                            if (audioFile.exists()) {
                                val audioEntry = ZipEntry("draft_${draft.id}/${audioFile.name}")
                                zipOut.putNextEntry(audioEntry)
                                audioFile.inputStream().use { input ->
                                    input.copyTo(zipOut)
                                }
                                zipOut.closeEntry()
                            }
                        }
                        zipOut.finish()
                    }
                }
            } ?: throw IllegalStateException("Could not open output stream for URI: $outputUri")
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
