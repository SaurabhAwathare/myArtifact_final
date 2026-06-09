package com.saurabh.artifact.backup

import android.content.Context
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.MnemonicGenerator
import com.saurabh.artifact.security.SecurityArchitecture
import com.saurabh.artifact.util.EncryptedStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for cloud storage. 
 * Implementations should handle the actual network transfer to Firebase, AWS, etc.
 */
interface CloudProvider {
    suspend fun upload(fileName: String, data: ByteArray): Result<String>
}

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftDao: com.saurabh.artifact.data.local.DraftDao,
    private val encryptedStorageManager: EncryptedStorageManager,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val cloudProvider: CloudProvider
) {

/**
 * Performs a full end-to-end encrypted backup of all drafts.
 * NOTE: This is currently redundant as BackupSyncWorker handles individual draft backups.
 * This can be used for a manual "Backup Now" feature or legacy migration.
 */
@Suppress("unused")
suspend fun performBackup(mnemonic: List<String>) = withContext(Dispatchers.IO) {
        // Ensure the manager is initialized with this mnemonic if it's a manual backup
        backupEncryptionManager.saveMnemonic(mnemonic.joinToString(" "))

        val drafts = draftDao.getAllDrafts()
        
        for (draft in drafts) {
            val localFile = File(draft.localAudioPath)
            if (localFile.exists()) {
                val decryptedData = encryptedStorageManager.getEncryptedInputStream(localFile).use { it.readBytes() }
                
                val encryptedData = backupEncryptionManager.encryptForBackup(decryptedData)
                
                cloudProvider.upload("draft_${draft.id}.enc", encryptedData)
            }
        }
        
        // Backup Database (Simplified metadata backup)
        // Note: In production, use VACUUM INTO for a consistent snapshot of the DB.
        val dbFile = context.getDatabasePath("artifact_db")
        if (dbFile.exists()) {
            val dbData = dbFile.readBytes()
            val encryptedDb = backupEncryptionManager.encryptForBackup(dbData)
            cloudProvider.upload("metadata.db.enc", encryptedDb)
        }
    }
}
