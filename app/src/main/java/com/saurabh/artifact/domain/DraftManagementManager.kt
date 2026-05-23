package com.saurabh.artifact.domain

import com.saurabh.artifact.data.local.DraftDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftManagementManager @Inject constructor(
    private val draftDao: DraftDao
) {
    suspend fun renameDraft(draftId: String, newTitle: String) = withContext(Dispatchers.IO) {
        draftDao.updateTitle(draftId, newTitle)
    }

    suspend fun deleteDraft(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.deleteById(draftId)
    }

    // Additional "Administrative" actions can be added here
}
