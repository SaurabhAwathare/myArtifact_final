package com.saurabh.artifact.backup

import android.content.Context
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.security.MnemonicGenerator
import com.saurabh.artifact.security.SecurityArchitecture
import com.saurabh.artifact.util.EncryptedStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Interface for cloud storage. 
 * Implementations should handle the actual network transfer to Firebase, AWS, etc.
 */
interface CloudProvider {
    suspend fun upload(fileName: String, data: ByteArray): Result<String>
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val encryptedStorageManager: EncryptedStorageManager,
    private val cloudProvider: CloudProvider
) {

    /**
     * Performs a full end-to-end encrypted backup of all drafts.
     */
    suspend fun performBackup(mnemonic: List<String>) = withContext(Dispatchers.IO) {
        val seed = MnemonicGenerator.toSeed(mnemonic)
        val backupKey = SecurityArchitecture.deriveBackupKey(seed.decodeToString(), "artifact_backup_salt".toByteArray())
        val secretKey = SecretKeySpec(backupKey, "AES")

        val drafts = draftDao.getAllDrafts()
        
        for (draft in drafts) {
            val localFile = File(draft.localAudioPath)
            if (localFile.exists()) {
                val decryptedData = encryptedStorageManager.getEncryptedInputStream(localFile).use { it.readBytes() }
                
                val encryptedData = encryptData(decryptedData, secretKey)
                
                cloudProvider.upload("draft_${draft.id}.enc", encryptedData)
            }
        }
        
        // Backup Database (Simplified metadata backup)
        // Note: In production, use VACUUM INTO for a consistent snapshot of the DB.
        val dbFile = context.getDatabasePath("artifact_db")
        if (dbFile.exists()) {
            val dbData = dbFile.readBytes()
            val encryptedDb = encryptData(dbData, secretKey)
            cloudProvider.upload("metadata.db.enc", encryptedDb)
        }
    }

    private fun encryptData(data: ByteArray, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        val encrypted = cipher.doFinal(data)
        return iv + encrypted // Prepend IV
    }
}
